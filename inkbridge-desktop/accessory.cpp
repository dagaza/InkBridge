#include "accessory.h"
#include "linux-adk.h"
#include "protocol.h"
#include "virtualstylus.h"
#include "backend.h"

#include <iostream>
#include <vector>
#include <atomic>
#include <libusb-1.0/libusb.h>
#include <QDebug>

using namespace std;

namespace InkBridge {
    volatile std::atomic<bool> stop_acc(false);
}

#define AOA_ACCESSORY_INTERFACE 0
#define AOA_ACCESSORY_EP_IN     0x81

// ----------------------------------------------------------------------------
// Helper: Extract Data
// ----------------------------------------------------------------------------
static void fillEventData(AccessoryEventData& data, const PenPacket* packet) {
    data.toolType = packet->toolType;
    data.action = packet->action;
    data.x = packet->x;
    data.y = packet->y;
    // Pressure is encoded as (event.pressure * 4096) on Android.
    // Dividing by 4096.0f restores the original 0.0-1.0 float range.
    // The previous value of 1000.0f was mismatched, causing values above
    // 1.0 that PressureTranslator would clamp, squashing most of the
    // pressure range to maximum.
    data.pressure = static_cast<float>(packet->pressure) / 4096.0f;
    
    // --- NEW: Copy Tilt Data ---
    data.tiltX = packet->tiltX;
    data.tiltY = packet->tiltY;
}

// ----------------------------------------------------------------------------
// Main Capture Loop
// ----------------------------------------------------------------------------
void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus)
{
    if (!conn || !virtualStylus) return;

    int ret = 0;
    int transferred = 0;
    unsigned char acc_buf[512]; 

    // Tracker variables to filter out redundant coordinate data
    int lastAction = -1;
    int lastTool = -1;

    ret = libusb_claim_interface(conn->getHandle(), AOA_ACCESSORY_INTERFACE);
    if (ret != 0) {
        cerr << "Error claiming interface: " << libusb_error_name(ret) << endl;
        return;
    }

    cout << "Accessory interface claimed. Starting capture loop..." << endl;
    AccessoryEventData eventData;

    while (!InkBridge::stop_acc) {
        ret = libusb_bulk_transfer(conn->getHandle(),
                                   AOA_ACCESSORY_EP_IN,
                                   acc_buf,
                                   sizeof(acc_buf),
                                   &transferred,
                                   200);

        if (ret < 0) {
            if (ret == LIBUSB_ERROR_TIMEOUT) continue; 
            if (ret == LIBUSB_ERROR_NO_DEVICE) {
                cout << "Device disconnected." << endl;
                break;
            }
            cerr << "Bulk transfer error: " << libusb_error_name(ret) << endl;
            break;
        }

        if (transferred == 0) continue;

        int processed = 0;
        while (processed + (int)sizeof(PenPacket) <= transferred) {
            const PenPacket* packet = reinterpret_cast<const PenPacket*>(acc_buf + processed);
            
            fillEventData(eventData, packet);

            // --- THE UPDATED DEBUGGER: STATE CHANGE ONLY ---
            if (Backend::isDebugMode) {
                // Only print if Action or ToolType changes (ignores coordinate/pressure jitter)
                if (eventData.action != lastAction || eventData.toolType != lastTool) {
                    qDebug() << "--- STATE CHANGE ---"
                             << "Action:" << eventData.action 
                             << "Tool:" << eventData.toolType;
                    
                    lastAction = eventData.action;
                    lastTool = eventData.toolType;
                }
            }
            // -----------------------------------------------

            virtualStylus->handleAccessoryEventData(&eventData);
            processed += sizeof(PenPacket);
        }
    }
    cout << "Capture loop finished." << endl;
}

// Legacy parser (unchanged, strictly for compilation)
bool parseAccessoryEventDataLine(const string &line, AccessoryEventData * accessoryEventData) {
    return false; // Disabled for now
}