#include "accessory.h"
#include "linux-adk.h"
#include "protocol.h"
#include "virtualstylus.h"
#include "mainwindow.h"

#include <iostream>
#include <vector>
#include <atomic>
#include <libusb-1.0/libusb.h>
#include <QDebug>

using namespace std;

namespace InkBridge {
    extern volatile std::atomic<bool> stop_acc;
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
    data.pressure = static_cast<float>(packet->pressure) / 1000.0f;
    
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

    // Claim the interface
    ret = libusb_claim_interface(conn->getHandle(), AOA_ACCESSORY_INTERFACE);
    if (ret != 0) {
        cerr << "Error claiming interface: " << libusb_error_name(ret) << endl;
        return;
    }

    cout << "Accessory interface claimed. Starting capture loop..." << endl;
    AccessoryEventData eventData;

    while (!InkBridge::stop_acc) {
        // Try to read up to 512 bytes
        ret = libusb_bulk_transfer(conn->getHandle(),
                                   AOA_ACCESSORY_EP_IN,
                                   acc_buf,
                                   sizeof(acc_buf),
                                   &transferred,
                                   200); // 200ms timeout

        // --- CRITICAL ERROR HANDLING RESTORED ---
        if (ret < 0) {
            // Timeouts are normal (it just means you didn't touch the screen in the last 200ms)
            if (ret == LIBUSB_ERROR_TIMEOUT) {
                continue; 
            }
            // Device disconnected
            if (ret == LIBUSB_ERROR_NO_DEVICE) {
                cout << "Device disconnected." << endl;
                break;
            }
            // Other errors (e.g., Pipe, Overflow)
            cerr << "Bulk transfer error: " << libusb_error_name(ret) << endl;
            break;
        }
        // ----------------------------------------

        if (transferred == 0) continue;

        // DEBUG: Print exactly what we got
        if (MainWindow::isDebugMode) {
             cout << "USB IN: Received " << transferred << " bytes" << endl;
        }

        int processed = 0;
        // Check if we have a full packet (22 bytes)
        while (processed + (int)sizeof(PenPacket) <= transferred) {
            const PenPacket* packet = reinterpret_cast<const PenPacket*>(acc_buf + processed);
            
            fillEventData(eventData, packet);

            if (MainWindow::isDebugMode) {
                qDebug() << "Packet [ T:" << eventData.toolType 
                         << " P:" << eventData.pressure 
                         << " TX:" << eventData.tiltX 
                         << " TY:" << eventData.tiltY 
                         << "]";
            }

            virtualStylus->handleAccessoryEventData(&eventData);
            processed += sizeof(PenPacket);
        }
        
        // Warning: If we received data but it wasn't enough for a packet (e.g. 14 bytes)
        if (processed == 0 && transferred > 0 && MainWindow::isDebugMode) {
            cout << "WARNING: Partial packet received! Got " << transferred 
                 << " bytes, needed " << sizeof(PenPacket) << endl;
        }
    }
    cout << "Capture loop finished." << endl;
}

// Legacy parser (unchanged, strictly for compilation)
bool parseAccessoryEventDataLine(const string &line, AccessoryEventData * accessoryEventData) {
    return false; // Disabled for now
}