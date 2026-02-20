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
#include "protocol.h"   // TouchFingerSlot, MT_MAX_SLOTS

class Error; // Forward declaration — full type only needed in .cpp

class VirtualStylus : public QObject
{
    Q_OBJECT
public:
    explicit VirtualStylus(DisplayScreenTranslator* accessoryScreen,
                           PressureTranslator*      pressureTranslator,
                           QObject*                 parent = nullptr);
    ~VirtualStylus();

    // Called by the USB/WiFi/Bluetooth receive loops for stylus events.
    void handleAccessoryEventData(AccessoryEventData* accessoryEventData);

    // Called by the receive loops for multi-touch finger events.
    // slots     : array of TouchFingerSlot describing all active/lifted fingers.
    // slotCount : number of valid entries in slots (1–MT_MAX_SLOTS).
    void handleTouchPacket(const TouchFingerSlot* fingerSlots, int slotCount);

    void initializeStylus();
    void initializeMTDevice();   // Exposed for testing; normally called lazily on first touch packet
    void destroyMTDevice();      // Call when a client disconnects
    void destroyStylus();

    // Geometry setters — applied to both stylus and MT coordinate mapping.
    void setTargetScreen(QRect geometry);
    void setTotalDesktopGeometry(QRect geometry);
    void setInputResolution(int width, int height);

    bool swapAxis = false;

private:
    // -------------------------------------------------------------------------
    // FILE DESCRIPTORS
    // -------------------------------------------------------------------------
    int m_stylusFd = -1;   // uinput fd for the stylus device  (was 'fd')
    int m_mtFd     = -1;   // uinput fd for the MT touch device (new)

    // -------------------------------------------------------------------------
    // THREADING & WATCHDOG
    // -------------------------------------------------------------------------
    std::mutex            m_mutex;
    std::thread           m_watchdogThread;
    std::atomic<bool>     m_watchdogRunning;
    std::atomic<int64_t>  m_lastEventTime;  // nanoseconds, updated each pen event

    void watchdogLoop();
    void performWatchdogReset();

    // -------------------------------------------------------------------------
    // STYLUS STATE (protected by m_mutex)
    // -------------------------------------------------------------------------
    bool m_isPenActive = false;
    int  m_activeTool  = -1;  // -1=none, 1=pen, 2=eraser

    void sendProximityOut(Error* err);
    void sendProximityIn(int tool, Error* err);

    // -------------------------------------------------------------------------
    // MT STATE (protected by m_mutex)
    //
    // m_slotTrackingId[i]: the tracking ID currently assigned to slot i.
    //   -1  means the slot is inactive (no finger in that slot).
    //   ≥0  means a finger is present; the value is the unique tracking ID
    //       we handed to the kernel for this contact.
    //
    // m_nextTrackingId: monotonically increasing counter. Each new finger
    //   contact gets a fresh ID so the kernel never confuses a new touch with
    //   a residual one from the previous gesture.
    // -------------------------------------------------------------------------
    int m_slotTrackingId[MT_MAX_SLOTS];  // initialised to -1 in constructor
    int m_nextTrackingId = 0;

    // Translates a normalised 0–32767 coordinate from the TouchPacket into the
    // absolute value the MT device should emit, applying the same screen-geometry
    // mapping used for stylus events.
    int32_t normToMtX(int32_t normX) const;
    int32_t normToMtY(int32_t normY) const;

    // -------------------------------------------------------------------------
    // DEPENDENCIES
    // -------------------------------------------------------------------------
    DisplayScreenTranslator* displayScreenTranslator;
    PressureTranslator*      pressureTranslator;

    void displayEventDebugInfo(AccessoryEventData* accessoryEventData);

    // -------------------------------------------------------------------------
    // GEOMETRY
    // -------------------------------------------------------------------------
    QRect targetScreenGeometry;
    QRect totalDesktopGeometry;
    int   inputWidth  = 0;
    int   inputHeight = 0;
};

#endif // VIRTUALSTYLUS_H