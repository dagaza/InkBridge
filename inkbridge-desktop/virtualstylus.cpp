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

// ----------------------------------------------------------------------------
// CONSTRUCTOR / DESTRUCTOR
// ----------------------------------------------------------------------------
VirtualStylus::VirtualStylus(DisplayScreenTranslator* displayScreenTranslator,
                             PressureTranslator*      pressureTranslator,
                             QObject*                 parent)
    : QObject(parent)
    , displayScreenTranslator(displayScreenTranslator)
    , pressureTranslator(pressureTranslator)
    , inputWidth(32767)
    , inputHeight(32767)
    , m_isPenActive(false)
{
    // Initialise all MT slots to inactive.
    for (int i = 0; i < MT_MAX_SLOTS; ++i)
        m_slotTrackingId[i] = -1;

    m_lastEventTime   = steady_clock::now().time_since_epoch().count();
    m_watchdogRunning = true;
    m_watchdogThread  = std::thread(&VirtualStylus::watchdogLoop, this);
}

VirtualStylus::~VirtualStylus() {
    m_watchdogRunning = false;
    if (m_watchdogThread.joinable())
        m_watchdogThread.join();
}

// ----------------------------------------------------------------------------
// INIT / DESTROY
// ----------------------------------------------------------------------------
void VirtualStylus::initializeStylus() {
    std::lock_guard<std::mutex> lock(m_mutex);
    Error* err = new Error();

    // Stylus device only — created once at startup, always present.
    // The MT device is NOT created here; it is created on demand when a
    // client connects (initializeMTDevice) and destroyed when it disconnects
    // (destroyMTDevice). This prevents libinput from seeing a permanent
    // virtual touch device and misclassifying it as a pointer, which would
    // steal the host mouse cursor even when no tablet is in use.
    m_stylusFd = init_uinput_stylus("inkbridge-pen", err);
    if (err->code != 0) {
        qDebug() << "Failed to init stylus uinput device:" << err->error_str;
    }

    delete err;
}

void VirtualStylus::initializeMTDevice() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_mtFd >= 0) return; // already open

    Error* err = new Error();
    m_mtFd = init_uinput_mt("inkbridge-touch", err);
    if (err->code != 0) {
        qDebug() << "Failed to init MT uinput device:" << err->error_str;
        m_mtFd = -1;
    } else {
        qDebug() << "MT touch device created.";
    }
    delete err;
}

void VirtualStylus::destroyMTDevice() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_mtFd >= 0) {
        destroy_uinput_device(m_mtFd);
        m_mtFd = -1;

        // Reset all slot tracking IDs so the next connection starts clean.
        for (int i = 0; i < MT_MAX_SLOTS; ++i)
            m_slotTrackingId[i] = -1;

        qDebug() << "MT touch device destroyed.";
    }
}

void VirtualStylus::destroyStylus() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_stylusFd >= 0) {
        destroy_uinput_device(m_stylusFd);
        m_stylusFd = -1;
    }
    if (m_mtFd >= 0) {
        destroy_uinput_device(m_mtFd);
        m_mtFd = -1;
    }
}

// ----------------------------------------------------------------------------
// STYLUS HELPERS (unchanged)
// ----------------------------------------------------------------------------
void VirtualStylus::sendProximityOut(Error* err) {
    send_uinput_event(m_stylusFd, ET_KEY,      EC_KEY_TOUCH,         0, err);
    send_uinput_event(m_stylusFd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);
    send_uinput_event(m_stylusFd, ET_KEY,      EC_KEY_TOOL_PEN,      0, err);
    send_uinput_event(m_stylusFd, ET_KEY,      EC_KEY_TOOL_RUBBER,   0, err);
    send_uinput_event(m_stylusFd, ET_SYNC,     EC_SYNC_REPORT,       0, err);
}

void VirtualStylus::sendProximityIn(int tool, Error* err) {
    if (tool == 2)
        send_uinput_event(m_stylusFd, ET_KEY, EC_KEY_TOOL_RUBBER, 1, err);
    else
        send_uinput_event(m_stylusFd, ET_KEY, EC_KEY_TOOL_PEN,    1, err);
    send_uinput_event(m_stylusFd, ET_SYNC, EC_SYNC_REPORT, 0, err);
}

// ----------------------------------------------------------------------------
// WATCHDOG (unchanged except fd rename)
// ----------------------------------------------------------------------------
void VirtualStylus::watchdogLoop() {
    while (m_watchdogRunning) {
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        int64_t last    = m_lastEventTime.load();
        int64_t now     = steady_clock::now().time_since_epoch().count();
        int64_t diff_ms = duration_cast<milliseconds>(nanoseconds(now - last)).count();

        if (diff_ms > 150)
            performWatchdogReset();
    }
}

void VirtualStylus::performWatchdogReset() {
    std::lock_guard<std::mutex> lock(m_mutex);

    // Re-check under lock to close the TOCTOU race.
    int64_t last    = m_lastEventTime.load();
    int64_t now     = steady_clock::now().time_since_epoch().count();
    int64_t diff_ms = duration_cast<milliseconds>(nanoseconds(now - last)).count();
    if (diff_ms <= 150) return;

    if (!m_isPenActive) return;

    if (Backend::isDebugMode) qDebug() << "WATCHDOG: Stream silent, forcing stylus lift.";

    Error* err = new Error();
    sendProximityOut(err);
    m_isPenActive = false;
    m_activeTool  = -1;
    delete err;
}

// ----------------------------------------------------------------------------
// MT COORDINATE HELPERS
//
// The MT device uses the same absolute coordinate space as the stylus
// (0–ABS_MAX_VAL) so that both devices map to the same screen area.
// The incoming TouchPacket coordinates are already normalised to 0–32767,
// which is our ABS_MAX_VAL, so in the simple case this is a 1:1 pass-through.
//
// When a target screen geometry is set (multi-monitor), we apply the same
// globalX/globalY percentage calculation used in handleAccessoryEventData
// so that MT coordinates land on the correct monitor.
// ----------------------------------------------------------------------------
int32_t VirtualStylus::normToMtX(int32_t normX) const {
    if (!targetScreenGeometry.isEmpty() && totalDesktopGeometry.width() > 0) {
        double xPercent      = static_cast<double>(normX) / 32767.0;
        double monitorPixelX = targetScreenGeometry.x() +
                               (xPercent * targetScreenGeometry.width());
        monitorPixelX = qBound(static_cast<double>(targetScreenGeometry.left()),
                               monitorPixelX,
                               static_cast<double>(targetScreenGeometry.right()));
        double globalX = monitorPixelX - totalDesktopGeometry.x();
        return static_cast<int32_t>((globalX / totalDesktopGeometry.width()) * ABS_MAX_VAL);
    }
    return normX; // No multi-monitor mapping — pass through directly.
}

int32_t VirtualStylus::normToMtY(int32_t normY) const {
    if (!targetScreenGeometry.isEmpty() && totalDesktopGeometry.height() > 0) {
        double yPercent      = static_cast<double>(normY) / 32767.0;
        double monitorPixelY = targetScreenGeometry.y() +
                               (yPercent * targetScreenGeometry.height());
        monitorPixelY = qBound(static_cast<double>(targetScreenGeometry.top()),
                               monitorPixelY,
                               static_cast<double>(targetScreenGeometry.bottom()));
        double globalY = monitorPixelY - totalDesktopGeometry.y();
        return static_cast<int32_t>((globalY / totalDesktopGeometry.height()) * ABS_MAX_VAL);
    }
    return normY;
}

// ----------------------------------------------------------------------------
// handleTouchPacket — Protocol B multi-touch dispatch
//
// Called from the USB/WiFi/Bluetooth receive loops for every PACKET_TYPE_TOUCH
// packet.  slots[i].slotId is the Android pointerId (0–9) which we map 1:1 to
// a Linux MT slot index, keeping the slot table stable across frames.
//
// Per-frame sequence for each finger:
//   ABS_MT_SLOT        <slotIndex>
//   ABS_MT_TRACKING_ID <id>          (-1 if lifting)
//   ABS_MT_POSITION_X  <x>           (omitted when lifting)
//   ABS_MT_POSITION_Y  <y>           (omitted when lifting)
//
// All fingers are emitted before the final SYN_REPORT so the kernel sees
// the full frame atomically.  Krita's touch handler then receives a single
// coherent update with all finger positions and computes pan/zoom itself.
//
// Tracking ID rules:
//   • A slot's tracking ID stays constant for the entire lifetime of one finger
//     contact (finger down → move → up).
//   • When a finger lifts (state == 0), ABS_MT_TRACKING_ID = -1 is sent and
//     m_slotTrackingId[slot] is reset to -1.
//   • The next finger to land in that slot gets a fresh ID from
//     m_nextTrackingId++, ensuring the kernel distinguishes new from old.
// ----------------------------------------------------------------------------
void VirtualStylus::handleTouchPacket(const TouchFingerSlot* fingerSlots, int slotCount) {
    if (m_mtFd < 0 || !fingerSlots || slotCount <= 0) return;

    std::lock_guard<std::mutex> lock(m_mutex);

    Error* err = new Error();

    for (int i = 0; i < slotCount; ++i) {
        const TouchFingerSlot& s = fingerSlots[i];

        // Clamp to valid slot range defensively — a misbehaving Android build
        // should not scribble over unrelated slots.
        int slotIdx = static_cast<int>(s.slotId);
        if (slotIdx < 0 || slotIdx >= MT_MAX_SLOTS) {
            if (Backend::isDebugMode)
                qDebug() << "MT: ignoring out-of-range slotId" << slotIdx;
            continue;
        }

        // Always write ABS_MT_SLOT first so the kernel knows which slot the
        // following events belong to.
        send_uinput_event(m_mtFd, EV_ABS, ABS_MT_SLOT, slotIdx, err);

        if (s.state == 1) {
            // --- Finger down or moving ---
            if (m_slotTrackingId[slotIdx] == -1) {
                // New contact: assign a fresh globally-unique tracking ID.
                m_slotTrackingId[slotIdx] = m_nextTrackingId++;
                // Wrap at 65535 to stay within our declared ABS_MT_TRACKING_ID
                // range. After 65535 contacts the oldest possible ID is long
                // gone so collisions are impossible in practice.
                if (m_nextTrackingId > 65535) m_nextTrackingId = 0;
            }
            send_uinput_event(m_mtFd, EV_ABS, ABS_MT_TRACKING_ID,
                              m_slotTrackingId[slotIdx], err);
            send_uinput_event(m_mtFd, EV_ABS, ABS_MT_POSITION_X,
                              normToMtX(s.x), err);
            send_uinput_event(m_mtFd, EV_ABS, ABS_MT_POSITION_Y,
                              normToMtY(s.y), err);

            if (Backend::isDebugMode) {
                qDebug() << "MT slot" << slotIdx
                         << "trackId" << m_slotTrackingId[slotIdx]
                         << "x" << s.x << "y" << s.y;
            }
        } else {
            // --- Finger lifted ---
            // Sending ABS_MT_TRACKING_ID = -1 tells the kernel the slot is
            // now free. XY is intentionally not updated for a lift event.
            send_uinput_event(m_mtFd, EV_ABS, ABS_MT_TRACKING_ID, -1, err);
            m_slotTrackingId[slotIdx] = -1;

            if (Backend::isDebugMode)
                qDebug() << "MT slot" << slotIdx << "lifted";
        }
    }

    // Commit the entire frame in one SYN_REPORT.
    send_uinput_event(m_mtFd, EV_SYN, SYN_REPORT, 0, err);

    delete err;
}

// ----------------------------------------------------------------------------
// handleAccessoryEventData (stylus path — unchanged except fd rename)
// ----------------------------------------------------------------------------
void VirtualStylus::handleAccessoryEventData(AccessoryEventData* accessoryEventData) {

    std::lock_guard<std::mutex> lock(m_mutex);

    m_lastEventTime = steady_clock::now().time_since_epoch().count();

    Error* err = new Error();
    uint64_t epoch = duration_cast<milliseconds>(
                         system_clock::now().time_since_epoch()).count();

    // -----------------------------------------------------------------------
    // 1. PARSE BUTTON AND ACTION
    // -----------------------------------------------------------------------
    bool isButtonPressed = (accessoryEventData->action & 32);
    int  baseAction      =  accessoryEventData->action & ~32;
    int  targetTool      = (isButtonPressed ||
                            accessoryEventData->toolType == ERASER_TOOL_TYPE) ? 2 : 1;

    bool isPositionEvent = (baseAction == ACTION_DOWN        ||
                            baseAction == ACTION_MOVE        ||
                            baseAction == ACTION_HOVER_MOVE  ||
                            baseAction == ACTION_HOVER_ENTER ||
                            baseAction == ACTION_UP);

    if (isPositionEvent) {
        bool isTouching = (baseAction == ACTION_DOWN || baseAction == ACTION_MOVE);

        // -------------------------------------------------------------------
        // 2. THREE-PHASE TOOL SWAP (unchanged)
        // -------------------------------------------------------------------
        if (targetTool != m_activeTool) {
            if (m_activeTool != -1)
                sendProximityOut(err);

            sendProximityIn(targetTool, err);
            m_activeTool = targetTool;

            if (isTouching) {
                send_uinput_event(m_stylusFd, ET_KEY,      EC_KEY_TOUCH,         1, err);
                send_uinput_event(m_stylusFd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 1, err);
                send_uinput_event(m_stylusFd, ET_SYNC,     EC_SYNC_REPORT,       0, err);
            }
        }

        m_isPenActive = true;

        // -------------------------------------------------------------------
        // 3. COORDINATE LOGIC (unchanged)
        // -------------------------------------------------------------------
        int32_t finalX = 0;
        int32_t finalY = 0;
        if (!targetScreenGeometry.isEmpty() && inputWidth > 0 && inputHeight > 0) {
            double calcX, calcY, maxInputX, maxInputY;
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
            double monitorPixelX = targetScreenGeometry.x() +
                                   (xPercent * targetScreenGeometry.width());
            double monitorPixelY = targetScreenGeometry.y() +
                                   (yPercent * targetScreenGeometry.height());
            if (monitorPixelX < targetScreenGeometry.left())
                monitorPixelX = targetScreenGeometry.left();
            if (monitorPixelX > targetScreenGeometry.right())
                monitorPixelX = targetScreenGeometry.right();
            if (monitorPixelY < targetScreenGeometry.top())
                monitorPixelY = targetScreenGeometry.top();
            if (monitorPixelY > targetScreenGeometry.bottom())
                monitorPixelY = targetScreenGeometry.bottom();
            double totalWidth  = totalDesktopGeometry.width();
            double totalHeight = totalDesktopGeometry.height();
            double globalX     = monitorPixelX - totalDesktopGeometry.x();
            double globalY     = monitorPixelY - totalDesktopGeometry.y();
            finalX = static_cast<int32_t>((globalX / totalWidth)  * ABS_MAX_VAL);
            finalY = static_cast<int32_t>((globalY / totalHeight) * ABS_MAX_VAL);
        } else {
            if (displayScreenTranslator->displayStyle == DisplayStyle::stretched) {
                finalX = displayScreenTranslator->getAbsXStretched(accessoryEventData);
                finalY = displayScreenTranslator->getAbsYStretched(accessoryEventData);
            } else {
                finalX = displayScreenTranslator->getAbsXFixed(accessoryEventData);
                finalY = displayScreenTranslator->getAbsYFixed(accessoryEventData);
            }
        }

        // -------------------------------------------------------------------
        // 4. SEND POSITION AND PRESSURE (Phase 3)
        // -------------------------------------------------------------------
        send_uinput_event(m_stylusFd, ET_ABSOLUTE, EC_ABSOLUTE_X, finalX, err);
        send_uinput_event(m_stylusFd, ET_ABSOLUTE, EC_ABSOLUTE_Y, finalY, err);

        if (isTouching) {
            int p = pressureTranslator->getResultingPressure(accessoryEventData);
            send_uinput_event(m_stylusFd, ET_KEY,      EC_KEY_TOUCH,         1, err);
            send_uinput_event(m_stylusFd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, p, err);
        } else {
            send_uinput_event(m_stylusFd, ET_KEY,      EC_KEY_TOUCH,         0, err);
            send_uinput_event(m_stylusFd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);
        }

        send_uinput_event(m_stylusFd, ET_ABSOLUTE, ABS_TILT_X,
                          accessoryEventData->tiltX, err);
        send_uinput_event(m_stylusFd, ET_ABSOLUTE, ABS_TILT_Y,
                          accessoryEventData->tiltY, err);

    } else {
        // -------------------------------------------------------------------
        // 5. EXIT LOGIC (unchanged)
        // -------------------------------------------------------------------
        if (m_activeTool != -1)
            sendProximityOut(err);

        m_isPenActive = false;
        m_activeTool  = -1;
    }

    // -----------------------------------------------------------------------
    // 6. FINAL SYNC (unchanged)
    // -----------------------------------------------------------------------
    send_uinput_event(m_stylusFd, ET_MSC,  EC_MSC_TIMESTAMP, epoch, err);
    send_uinput_event(m_stylusFd, ET_SYNC, EC_SYNC_REPORT,   0,     err);

    delete err;
}

// ----------------------------------------------------------------------------
// GEOMETRY SETTERS
// ----------------------------------------------------------------------------
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

void VirtualStylus::displayEventDebugInfo(AccessoryEventData* accessoryEventData) {
    Q_UNUSED(accessoryEventData);
}