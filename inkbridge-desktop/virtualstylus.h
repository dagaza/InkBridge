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

class Error; // Forward declaration — full type only needed in .cpp

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
    std::mutex            m_mutex;
    std::thread           m_watchdogThread;
    std::atomic<bool>     m_watchdogRunning;
    std::atomic<int64_t>  m_lastEventTime; // Stores time in nanoseconds

    bool isPenActive = false; // Protected by m_mutex
    int  m_activeTool = -1;   // -1 = None, 1 = Pen, 2 = Eraser; protected by m_mutex

    void watchdogLoop();         // Background loop checking for timeouts
    void performWatchdogReset(); // Logic to force-lift the pen on timeout

    // --- TOOL SWAP HELPERS ---
    // These implement the kernel-mandated three-phase proximity protocol.
    // Must be called with m_mutex already held.
    void sendProximityOut(Error* err); // Phase 1: de-assert old tool, sync
    void sendProximityIn(int tool, Error* err); // Phase 2: assert new tool, sync

    DisplayScreenTranslator * displayScreenTranslator;
    PressureTranslator      * pressureTranslator;

    void displayEventDebugInfo(AccessoryEventData * accessoryEventData);

    // --- VARIABLES ---
    QRect targetScreenGeometry;
    QRect totalDesktopGeometry;
    int   inputWidth  = 0;
    int   inputHeight = 0;
};

#endif // VIRTUALSTYLUS_H