#include <QGuiApplication>
#include <chrono>
#include <QDebug>
#include <linux/input.h>
#include "virtualstylus.h"
#include "error.h"
#include "uinput.h"
#include "constants.h"
#include "accessory.h"
#include "pressuretranslator.h"
#include "backend.h"

using namespace std::chrono;

const int ACTION_HOVER_ENTER = 9;
const int ACTION_HOVER_EXIT  = 10;

VirtualStylus::VirtualStylus(DisplayScreenTranslator * displayScreenTranslator,
                             PressureTranslator * pressureTranslator,
                             QObject *parent) : QObject(parent)
{
    this->displayScreenTranslator = displayScreenTranslator;
    this->pressureTranslator      = pressureTranslator;
    this->inputWidth              = 32767;
    this->inputHeight             = 32767;
    this->isPenActive             = false;

    m_lastEventTime   = steady_clock::now().time_since_epoch().count();
    m_watchdogRunning = true;
    m_watchdogThread  = std::thread(&VirtualStylus::watchdogLoop, this);
}

VirtualStylus::~VirtualStylus() {
    m_watchdogRunning = false;
    if (m_watchdogThread.joinable()) {
        m_watchdogThread.join();
    }
}

void VirtualStylus::initializeStylus(){
    std::lock_guard<std::mutex> lock(m_mutex);
    Error * err = new Error();
    const char* deviceName = "pen-emu";
    fd = init_uinput_stylus(deviceName, err);
    delete err;
}

// ---------------------------------------------------------------------------
// HELPER: sendProximityOut
//
// Sends a complete, self-contained proximity-out report (Phase 1 of the
// three-phase tool swap). After this sync, the kernel considers the device
// fully out of range with no tool present.
//
// Must be called before asserting any new tool bit. Covers both hover and
// touch cases — clearing touch+pressure first ensures the sync is clean
// regardless of whether the nib was contacting the surface.
//
// Caller is responsible for updating m_activeTool and isPenActive.
// ---------------------------------------------------------------------------
void VirtualStylus::sendProximityOut(Error* err) {
    // Release touch and pressure before the proximity-out sync.
    send_uinput_event(fd, ET_KEY,      EC_KEY_TOUCH,         0, err);
    send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);

    // Clear both tool bits defensively. If only one was set the other is a
    // no-op (0->0), which the kernel ignores without harm.
    send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN,    0, err);
    send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 0, err);

    // Phase 1 commit: kernel now sees no tool in range.
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT, 0, err);
}

// ---------------------------------------------------------------------------
// HELPER: sendProximityIn
//
// Asserts the new tool bit in its own dedicated sync report (Phase 2).
// No position or pressure is included — those come in Phase 3. Sending
// axis data before the tool-present bit is latched is a reliable trigger
// for SYN_DROPPED on both X11 (evdev) and Wayland (libinput).
// ---------------------------------------------------------------------------
void VirtualStylus::sendProximityIn(int tool, Error* err) {
    if (tool == 2) {
        send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 1, err);
    } else {
        send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN,    1, err);
    }

    // Phase 2 commit: kernel now sees the new tool in range, ready for data.
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT, 0, err);
}

// ---------------------------------------------------------------------------
// Watchdog loop — runs on a dedicated thread, checks every 50 ms whether
// the event stream has gone silent and forces a clean reset if so.
// ---------------------------------------------------------------------------
void VirtualStylus::watchdogLoop() {
    while (m_watchdogRunning) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        int64_t last    = m_lastEventTime.load();
        int64_t now     = steady_clock::now().time_since_epoch().count();
        int64_t diff_ms = duration_cast<milliseconds>(nanoseconds(now - last)).count();

        if (diff_ms > 150) {
            performWatchdogReset();
        }
    }
}

void VirtualStylus::performWatchdogReset() {
    std::lock_guard<std::mutex> lock(m_mutex);

    // Re-check the timestamp now that we hold the lock. If the main thread
    // processed an event between our check above and here, the diff will be
    // small and we bail — this closes the race condition.
    int64_t last    = m_lastEventTime.load();
    int64_t now     = steady_clock::now().time_since_epoch().count();
    int64_t diff_ms = duration_cast<milliseconds>(nanoseconds(now - last)).count();
    if (diff_ms <= 150) return;

    if (!isPenActive) return;

    if(Backend::isDebugMode) qDebug() << "WATCHDOG: Stream silent, forcing stylus lift.";

    Error * err = new Error();

    // Use the shared helper so the watchdog reset produces the same
    // kernel-valid proximity-out sequence as a normal tool swap.
    sendProximityOut(err);

    isPenActive  = false;
    m_activeTool = -1;

    delete err;
}

void VirtualStylus::handleAccessoryEventData(AccessoryEventData * accessoryEventData){

    std::lock_guard<std::mutex> lock(m_mutex);

    // Timestamp is updated inside the lock so the watchdog's re-check
    // (which also runs under the lock) always sees the current value.
    m_lastEventTime = steady_clock::now().time_since_epoch().count();

    Error * err = new Error();
    uint64_t epoch = duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();

    // -----------------------------------------------------------------------
    // 1. PARSE BUTTON AND ACTION
    // -----------------------------------------------------------------------
    bool isButtonPressed = (accessoryEventData->action & 32);
    int  baseAction      =  accessoryEventData->action & ~32;

    int targetTool = (isButtonPressed || accessoryEventData->toolType == ERASER_TOOL_TYPE) ? 2 : 1;

    bool isPositionEvent = (baseAction == ACTION_DOWN        ||
                            baseAction == ACTION_MOVE        ||
                            baseAction == ACTION_HOVER_MOVE  ||
                            baseAction == ACTION_HOVER_ENTER ||
                            baseAction == ACTION_UP);

    if (isPositionEvent) {
        bool isTouching = (baseAction == ACTION_DOWN || baseAction == ACTION_MOVE);

        // -------------------------------------------------------------------
        // 2. THREE-PHASE TOOL SWAP
        //
        // Runs whenever the kernel's latched tool differs from what we want.
        // Covers all cases: first entry, hover swap, touch swap, re-entry
        // after a watchdog reset.
        //
        // The kernel's evdev/libinput layer requires tool bit transitions to
        // be isolated in their own sync reports. Merging them with position
        // or pressure data — or with each other — causes SYN_DROPPED, which
        // stalls ALL input devices on the seat (explaining the mouse freeze).
        //
        //   Phase 1 — sendProximityOut: clear old tool, sync
        //   Phase 2 — sendProximityIn:  assert new tool, sync
        //   Phase 3 — section 4 below: send position/pressure, sync
        // -------------------------------------------------------------------
        if (targetTool != m_activeTool) {

            // Phase 1: If any tool was previously active (including hover-only),
            // send a clean proximity-out before touching any tool bits.
            // This is unconditional on tool type — hover doesn't need a touch
            // lift, but it absolutely needs the tool bit cleared and synced.
            if (m_activeTool != -1) {
                sendProximityOut(err);
            }

            // Phase 2: Assert the new tool bit in its own sync report.
            sendProximityIn(targetTool, err);

            m_activeTool = targetTool;

            // If the swap happened mid-stroke (nib was touching), the kernel
            // now has the new tool in range but no active touch. We must send
            // an explicit BTN_TOUCH=1 + minimal pressure in a dedicated sync
            // before Phase 3, otherwise the kernel receives pressure > 0
            // without a prior touch-down and silently discards the stroke.
            if (isTouching) {
                send_uinput_event(fd, ET_KEY,      EC_KEY_TOUCH,         1, err);
                send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 1, err);
                send_uinput_event(fd, ET_SYNC,     EC_SYNC_REPORT,       0, err);
                // The real pressure value is sent in section 4 and committed
                // by the final sync at the bottom of this function.
            }
        }

        isPenActive = true;

        // -------------------------------------------------------------------
        // 3. COORDINATE LOGIC (UNMODIFIED)
        // -------------------------------------------------------------------
        int32_t finalX = 0;
        int32_t finalY = 0;
        if (!targetScreenGeometry.isEmpty() && inputWidth > 0 && inputHeight > 0) {
            double calcX, calcY;
            double maxInputX, maxInputY;
            if (this->swapAxis) {
                calcX     = accessoryEventData->y;
                calcY     = inputWidth - accessoryEventData->x;
                maxInputX = inputHeight;
                maxInputY = inputWidth;
            } else {
                calcX     = accessoryEventData->x;
                calcY     = accessoryEventData->y;
                maxInputX = inputWidth;
                maxInputY = inputHeight;
            }
            double xPercent      = calcX / maxInputX;
            double yPercent      = calcY / maxInputY;
            double monitorPixelX = targetScreenGeometry.x() + (xPercent * targetScreenGeometry.width());
            double monitorPixelY = targetScreenGeometry.y() + (yPercent * targetScreenGeometry.height());
            if (monitorPixelX < targetScreenGeometry.left())   monitorPixelX = targetScreenGeometry.left();
            if (monitorPixelX > targetScreenGeometry.right())  monitorPixelX = targetScreenGeometry.right();
            if (monitorPixelY < targetScreenGeometry.top())    monitorPixelY = targetScreenGeometry.top();
            if (monitorPixelY > targetScreenGeometry.bottom()) monitorPixelY = targetScreenGeometry.bottom();
            double totalWidth  = totalDesktopGeometry.width();
            double totalHeight = totalDesktopGeometry.height();
            double globalX = monitorPixelX - totalDesktopGeometry.x();
            double globalY = monitorPixelY - totalDesktopGeometry.y();
            finalX = (int32_t)((globalX / totalWidth)  * ABS_MAX_VAL);
            finalY = (int32_t)((globalY / totalHeight) * ABS_MAX_VAL);
        } else {
            if(displayScreenTranslator->displayStyle == DisplayStyle::stretched){
                finalX = displayScreenTranslator->getAbsXStretched(accessoryEventData);
                finalY = displayScreenTranslator->getAbsYStretched(accessoryEventData);
            } else {
                finalX = displayScreenTranslator->getAbsXFixed(accessoryEventData);
                finalY = displayScreenTranslator->getAbsYFixed(accessoryEventData);
            }
        }

        // -------------------------------------------------------------------
        // 4. SEND POSITION AND PRESSURE (Phase 3 data — committed by final sync)
        // -------------------------------------------------------------------
        send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_X, finalX, err);
        send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_Y, finalY, err);

        if (isTouching) {
            int p = pressureTranslator->getResultingPressure(accessoryEventData);
            send_uinput_event(fd, ET_KEY,      EC_KEY_TOUCH,         1, err);
            send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, p, err);
        } else {
            send_uinput_event(fd, ET_KEY,      EC_KEY_TOUCH,         0, err);
            send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);
        }

        send_uinput_event(fd, ET_ABSOLUTE, ABS_TILT_X, accessoryEventData->tiltX, err);
        send_uinput_event(fd, ET_ABSOLUTE, ABS_TILT_Y, accessoryEventData->tiltY, err);

    } else {
        // -------------------------------------------------------------------
        // 5. EXIT LOGIC
        //
        // Use the shared proximity-out helper for the same clean sequence
        // the kernel expects — identical to Phase 1 of a tool swap.
        // -------------------------------------------------------------------
        if (m_activeTool != -1) {
            sendProximityOut(err);
        }

        isPenActive  = false;
        m_activeTool = -1;

        // sendProximityOut already committed a sync. The unconditional sync
        // at the bottom will fire with no pending events — the kernel treats
        // an empty sync as a harmless no-op, so no SYN_DROPPED risk here.
    }

    // -----------------------------------------------------------------------
    // 6. FINAL SYNC
    //
    // For position events: this is Phase 3 — commits position and pressure.
    // For exit events: this is an empty no-op sync after the proximity-out
    //   that sendProximityOut already committed. Harmless.
    // For tool-swap frames: this is the third and final phase.
    // For normal frames (no swap): this is the only sync in the function.
    //
    // The timestamp is included here rather than in each sub-sync so that
    // only the frame-completing report carries timing data.
    // -----------------------------------------------------------------------
    send_uinput_event(fd, ET_MSC,  EC_MSC_TIMESTAMP, epoch, err);
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT,   0,     err);

    delete err;
}

void VirtualStylus::displayEventDebugInfo(AccessoryEventData * accessoryEventData){
   Q_UNUSED(accessoryEventData);
}

void VirtualStylus::destroyStylus(){
    std::lock_guard<std::mutex> lock(m_mutex);
    if(fd >= 0) {
        // cleanup logic
    }
}

void VirtualStylus::setTargetScreen(QRect geometry) {
    this->targetScreenGeometry = geometry;
}

void VirtualStylus::setTotalDesktopGeometry(QRect geometry) {
    this->totalDesktopGeometry = geometry;
}

void VirtualStylus::setInputResolution(int width, int height) {
    this->inputWidth  = width;
    this->inputHeight = height;
}