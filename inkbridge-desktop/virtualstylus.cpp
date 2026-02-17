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
const int ACTION_HOVER_EXIT = 10;

VirtualStylus::VirtualStylus(DisplayScreenTranslator * displayScreenTranslator,
                             PressureTranslator * pressureTranslator,
                             QObject *parent) : QObject(parent)
{
    this->displayScreenTranslator = displayScreenTranslator;
    this->pressureTranslator = pressureTranslator;
    this->inputWidth = 32767;
    this->inputHeight = 32767;
    this->isPenActive = false;

    // --- SETUP WATCHDOG (STD::THREAD) ---
    // We initialize the timestamp to now so it doesn't fire immediately
    m_lastEventTime = steady_clock::now().time_since_epoch().count();
    m_watchdogRunning = true;
    
    // Launch the dedicated safety thread
    m_watchdogThread = std::thread(&VirtualStylus::watchdogLoop, this);
}

VirtualStylus::~VirtualStylus() {
    // Stop the thread safely
    m_watchdogRunning = false;
    if (m_watchdogThread.joinable()) {
        m_watchdogThread.join();
    }
}

void VirtualStylus::initializeStylus(){
    std::lock_guard<std::mutex> lock(m_mutex); // Safety lock
    Error * err = new Error();
    const char* deviceName = "pen-emu";
    fd = init_uinput_stylus(deviceName, err);
    delete err;
}

// --- NEW: Thread-Safe Watchdog Loop ---
void VirtualStylus::watchdogLoop() {
    while (m_watchdogRunning) {
        // Sleep for 50ms to save CPU
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        // Calculate time since last packet
        int64_t last = m_lastEventTime.load();
        int64_t now = steady_clock::now().time_since_epoch().count();
        int64_t diff_ms = duration_cast<milliseconds>(nanoseconds(now - last)).count();

        // If > 150ms has passed and pen is active, force reset
        // We use a lock inside performWatchdogReset to be safe
        if (diff_ms > 150) {
            performWatchdogReset();
        }
    }
}

void VirtualStylus::performWatchdogReset() {
    std::lock_guard<std::mutex> lock(m_mutex); // LOCK MUTEX

    if (!isPenActive) return; // Already reset

    if(Backend::isDebugMode) qDebug() << "WATCHDOG: Stream silent, forcing stylus lift.";

    Error * err = new Error();
    
    // FORCE RELEASE EVERYTHING
    send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 0, err);
    send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 0, err);
    send_uinput_event(fd, ET_KEY, EC_KEY_TOUCH, 0, err);
    send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);
    
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT, 0, err);
    
    isPenActive = false;
    delete err;
}

void VirtualStylus::handleAccessoryEventData(AccessoryEventData * accessoryEventData){
    // 1. Kick the Dog (Update timestamp)
    m_lastEventTime = steady_clock::now().time_since_epoch().count();

    // 2. Lock critical section
    // This ensures we don't write to UInput while the Watchdog is trying to reset it
    std::lock_guard<std::mutex> lock(m_mutex); 

    Error * err = new Error();
    uint64_t epoch = duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();

    // --- S-PEN BUTTON LOGIC ---
    bool isButtonPressed = (accessoryEventData->action & 32);
    int baseAction = accessoryEventData->action & ~32; 

    if(Backend::isDebugMode) {
        qDebug() << "INCOMING: Action=" << baseAction 
                 << " Button=" << isButtonPressed
                 << " X=" << accessoryEventData->x;
    }
    
    bool isPositionEvent = (baseAction == ACTION_DOWN || 
                            baseAction == ACTION_MOVE ||
                            baseAction == ACTION_HOVER_MOVE ||
                            baseAction == ACTION_HOVER_ENTER ||
                            baseAction == ACTION_UP);

    if (isPositionEvent) {
        
        // --- TOOL LOGIC ---
        if(isButtonPressed || accessoryEventData->toolType == ERASER_TOOL_TYPE){
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 0, err);
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 1, err);
        } else {
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 1, err);
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 0, err);
        }
        isPenActive = true; 

        // --- COORDINATE LOGIC ---
        int32_t finalX = 0;
        int32_t finalY = 0;

        if (!targetScreenGeometry.isEmpty() && inputWidth > 0 && inputHeight > 0) {
            double calcX, calcY;
            double maxInputX, maxInputY;

            if (this->swapAxis) {
                calcX = accessoryEventData->y;
                calcY = inputWidth - accessoryEventData->x;
                maxInputX = inputHeight;
                maxInputY = inputWidth;
            } else {
                calcX = accessoryEventData->x;
                calcY = accessoryEventData->y;
                maxInputX = inputWidth;
                maxInputY = inputHeight;
            }

            double xPercent = calcX / maxInputX;
            double yPercent = calcY / maxInputY;

            double monitorPixelX = targetScreenGeometry.x() + (xPercent * targetScreenGeometry.width());
            double monitorPixelY = targetScreenGeometry.y() + (yPercent * targetScreenGeometry.height());

            // Clamping
            if (monitorPixelX < targetScreenGeometry.left()) monitorPixelX = targetScreenGeometry.left();
            if (monitorPixelX > targetScreenGeometry.right()) monitorPixelX = targetScreenGeometry.right();
            if (monitorPixelY < targetScreenGeometry.top()) monitorPixelY = targetScreenGeometry.top();
            if (monitorPixelY > targetScreenGeometry.bottom()) monitorPixelY = targetScreenGeometry.bottom();

            double totalWidth = totalDesktopGeometry.width();
            double totalHeight = totalDesktopGeometry.height();
            double globalX = monitorPixelX - totalDesktopGeometry.x(); 
            double globalY = monitorPixelY - totalDesktopGeometry.y();

            finalX = (int32_t)((globalX / totalWidth) * ABS_MAX_VAL);
            finalY = (int32_t)((globalY / totalHeight) * ABS_MAX_VAL);
        } else {
            // Fallback
            if(displayScreenTranslator->displayStyle == DisplayStyle::stretched){
                finalX = displayScreenTranslator->getAbsXStretched(accessoryEventData);
                finalY = displayScreenTranslator->getAbsYStretched(accessoryEventData);
            } else {
                finalX = displayScreenTranslator->getAbsXFixed(accessoryEventData);
                finalY = displayScreenTranslator->getAbsYFixed(accessoryEventData);
            }
        }

        // --- TOUCH LOGIC ---
        bool isTouching = (baseAction == ACTION_DOWN || baseAction == ACTION_MOVE);

        int pressure = 0;
        if (isTouching) {
            pressure = pressureTranslator->getResultingPressure(accessoryEventData);
            send_uinput_event(fd, ET_KEY, EC_KEY_TOUCH, 1, err);
        } else {
            pressure = 0;
            send_uinput_event(fd, ET_KEY, EC_KEY_TOUCH, 0, err);
        }

        // --- SEND EVENTS ---
        send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_X, finalX, err);
        send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_Y, finalY, err);
        send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, pressure, err);
        send_uinput_event(fd, ET_ABSOLUTE, ABS_TILT_X, accessoryEventData->tiltX, err);
        send_uinput_event(fd, ET_ABSOLUTE, ABS_TILT_Y, accessoryEventData->tiltY, err);

    } else {
        // --- EXIT LOGIC ---
        send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 0, err);
        send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 0, err);
        
        // Safety: Ensure touch is off
        send_uinput_event(fd, ET_KEY, EC_KEY_TOUCH, 0, err);
        send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);
        
        isPenActive = false;
        
        // Timer stop logic removed (replaced by automatic thread checks)
    }

    send_uinput_event(fd, ET_MSC, EC_MSC_TIMESTAMP, epoch, err);
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT, 0, err);
    delete err;
}

void VirtualStylus::displayEventDebugInfo(AccessoryEventData * accessoryEventData){
   Q_UNUSED(accessoryEventData);
}

void VirtualStylus::destroyStylus(){
    std::lock_guard<std::mutex> lock(m_mutex); // Lock before closing
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
    this->inputWidth = width;
    this->inputHeight = height;
}