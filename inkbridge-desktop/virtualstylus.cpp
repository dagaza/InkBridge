#include <QGuiApplication>
#include <chrono>
#include <QDebug>
#include <linux/input.h> // Required for ABS_TILT_X constants
#include "virtualstylus.h"
#include "error.h"
#include "uinput.h" 
#include "constants.h"
#include "accessory.h"
#include "pressuretranslator.h"
#include "backend.h"

using namespace std::chrono;

// ACTION_CANCEL is already in uinput.h, so we don't redefine it here.
const int ACTION_HOVER_ENTER = 9;
const int ACTION_HOVER_EXIT = 10;

// --- FIX 1: Updated Constructor to match header (added parent) ---
VirtualStylus::VirtualStylus(DisplayScreenTranslator * displayScreenTranslator,
                             PressureTranslator * pressureTranslator,
                             QObject *parent) : QObject(parent)
{
    this->displayScreenTranslator = displayScreenTranslator;
    this->pressureTranslator = pressureTranslator;
    this->inputWidth = 32767;
    this->inputHeight = 32767;

    // --- SETUP WATCHDOG TIMER ---
    watchdogTimer = new QTimer(this);
    watchdogTimer->setSingleShot(true); // Fire once per event gap
    // Connect the timer signal to our reset slot
    connect(watchdogTimer, &QTimer::timeout, this, &VirtualStylus::onWatchdogTimeout);
}

void VirtualStylus::initializeStylus(){
    Error * err = new Error();
    const char* deviceName = "pen-emu";
    fd = init_uinput_stylus(deviceName, err);
    delete err;
}

// --- FIX 2: Added the Watchdog Reset Function ---
void VirtualStylus::onWatchdogTimeout() {
    if (!isPenActive) return; // Already reset, do nothing

    if(Backend::isDebugMode) qDebug() << "WATCHDOG: Stream silent, forcing stylus lift.";

    Error * err = new Error();
    
    // FORCE RELEASE EVERYTHING
    send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 0, err);
    send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 0, err);
    send_uinput_event(fd, ET_KEY, EC_KEY_TOUCH, 0, err); // This unlocks the mouse!
    send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);
    
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT, 0, err);
    
    isPenActive = false;
    delete err;
}

void VirtualStylus::handleAccessoryEventData(AccessoryEventData * accessoryEventData){
    // --- RESET WATCHDOG ---
    // Every time we receive a packet, we push the "bomb" back by 150ms.
    // If the stream stops (Samsung gesture, network lag), the timer expires and resets the mouse.
    if (watchdogTimer->isActive()) watchdogTimer->stop();
    watchdogTimer->start(150);

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
        
        // If we exit cleanly, we can stop the watchdog
        watchdogTimer->stop();
    }

    send_uinput_event(fd, ET_MSC, EC_MSC_TIMESTAMP, epoch, err);
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT, 0, err);
    delete err;
}

void VirtualStylus::displayEventDebugInfo(AccessoryEventData * accessoryEventData){
   Q_UNUSED(accessoryEventData); // Silence compiler warning
}

void VirtualStylus::destroyStylus(){
    if(fd >= 0) {
        // Cleanup uinput device if needed
        // ioctl(fd, UI_DEV_DESTROY);
        // close(fd);
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