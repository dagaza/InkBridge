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

const int ACTION_HOVER_ENTER = 9;
const int ACTION_HOVER_EXIT = 10;

VirtualStylus::VirtualStylus(DisplayScreenTranslator * displayScreenTranslator,
                             PressureTranslator * pressureTranslator){
    this->displayScreenTranslator = displayScreenTranslator;
    this->pressureTranslator = pressureTranslator;

    // --- FIX: Initialize Input Resolution ---
    // Since Android now sends normalized data (0..32767), 
    // we hardcode the input resolution to match.
    this->inputWidth = 32767;
    this->inputHeight = 32767;
}

void VirtualStylus::initializeStylus(){
    Error * err = new Error();
    const char* deviceName = "pen-emu";
    fd = init_uinput_stylus(deviceName, err);
    delete err;
}

void VirtualStylus::handleAccessoryEventData(AccessoryEventData * accessoryEventData){
    Error * err = new Error();
    uint64_t epoch = duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();

    if(Backend::isDebugMode) {
        qDebug() << "INCOMING: Action=" << accessoryEventData->action 
                 << " X=" << accessoryEventData->x
                 << " TiltX=" << accessoryEventData->tiltX
                 << " TiltY=" << accessoryEventData->tiltY;
    }
    
    bool isPositionEvent = (accessoryEventData->action == ACTION_DOWN || 
                            accessoryEventData->action == ACTION_MOVE ||
                            accessoryEventData->action == ACTION_HOVER_MOVE ||
                            accessoryEventData->action == ACTION_HOVER_ENTER ||
                            accessoryEventData->action == ACTION_UP);

    if (isPositionEvent) {
        
        // Tool Logic
        if(accessoryEventData->toolType == ERASER_TOOL_TYPE){
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 0, err);
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 1, err);
        } else {
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 1, err);
            send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 0, err);
        }
        isPenActive = true; 

        // Coordinate Logic
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

        // Touch Logic
        bool isTouching = (accessoryEventData->action == ACTION_DOWN || 
                           accessoryEventData->action == ACTION_MOVE);

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
        
        // --- NEW: INJECT TILT ---
        // Note: We use standard Linux constants (ABS_TILT_X) because EC_... 
        // constants might not include tilt yet in your project.
        send_uinput_event(fd, ET_ABSOLUTE, ABS_TILT_X, accessoryEventData->tiltX, err);
        send_uinput_event(fd, ET_ABSOLUTE, ABS_TILT_Y, accessoryEventData->tiltY, err);

    } else {
        // Exit Logic
        send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_PEN, 0, err);
        send_uinput_event(fd, ET_KEY, EC_KEY_TOOL_RUBBER, 0, err);
        send_uinput_event(fd, ET_ABSOLUTE, EC_ABSOLUTE_PRESSURE, 0, err);
        isPenActive = false;
    }

    send_uinput_event(fd, ET_MSC, EC_MSC_TIMESTAMP, epoch, err);
    send_uinput_event(fd, ET_SYNC, EC_SYNC_REPORT, 0, err);
    delete err;
}

void VirtualStylus::displayEventDebugInfo(AccessoryEventData * accessoryEventData){
   // Implemented inline above
}

void VirtualStylus::destroyStylus(){
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