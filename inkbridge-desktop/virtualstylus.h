#ifndef VIRTUALSTYLUS_H
#define VIRTUALSTYLUS_H

#include <QObject>
#include <QScreen>
#include <QRect>
#include <mutex>
#include <thread>
#include <atomic>
#include "accessory.h"
#include "displayscreentranslator.h"
#include "pressuretranslator.h"

// We inherit from QObject for parent-child memory management, 
// but we now use std::thread for the watchdog to avoid QTimer threading issues.
class VirtualStylus : public QObject
{
    Q_OBJECT 

public:
    // Constructor
    explicit VirtualStylus(DisplayScreenTranslator * accessoryScreen, 
                           PressureTranslator *pressureTranslator, 
                           QObject *parent = nullptr);
    
    // Destructor (Required to stop the thread safely)
    ~VirtualStylus();

    void handleAccessoryEventData(AccessoryEventData * accessoryEventData);
    void initializeStylus();
    void destroyStylus();

    // --- GEOMETRY SETTERS ---
    void setTargetScreen(QRect geometry); 
    void setTotalDesktopGeometry(QRect geometry); 
    void setInputResolution(int width, int height); 

    bool swapAxis = false;

private:
    int fd;
    
    // --- THREADING & WATCHDOG ---
    // We use a mutex to ensure the 'Watchdog Thread' and 'USB Thread' 
    // don't try to write to the file descriptor (fd) at the exact same time.
    std::mutex m_mutex; 
    std::thread m_watchdogThread;
    std::atomic<bool> m_watchdogRunning;
    std::atomic<int64_t> m_lastEventTime; // Stores time in milliseconds
    bool isPenActive = false; // Protected by m_mutex

    void watchdogLoop();      // The background loop checking for timeouts
    void performWatchdogReset(); // The logic to lift the pen

    DisplayScreenTranslator * displayScreenTranslator;
    PressureTranslator * pressureTranslator;
    void displayEventDebugInfo(AccessoryEventData * accessoryEventData);
    
    // --- VARIABLES ---
    QRect targetScreenGeometry; 
    QRect totalDesktopGeometry;
    int inputWidth = 0;
    int inputHeight = 0;
};

#endif // VIRTUALSTYLUS_H