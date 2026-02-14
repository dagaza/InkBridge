/*
 * Linux ADK - accessory.c
 *
 * Copyright (C) 2013 - Gary Bisson <bisson.gary@gmail.com>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

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

// Import the stop signal from linux-adk.cpp
namespace InkBridge {
    extern volatile std::atomic<bool> stop_acc;
}

// Legacy constants for AOA
#define AOA_ACCESSORY_INTERFACE 0
#define AOA_ACCESSORY_EP_IN     0x81

// ----------------------------------------------------------------------------
// Helper: Extract Data (Refactored for Safety)
// ----------------------------------------------------------------------------
static void fillEventData(AccessoryEventData& data, const PenPacket* packet) {
    data.toolType = packet->toolType;
    data.action = packet->action;
    data.x = packet->x;
    data.y = packet->y;
    // Normalize pressure to 0.0 - 1.0 range
    data.pressure = static_cast<float>(packet->pressure) / 1000.0f;
}

// ----------------------------------------------------------------------------
// Main Capture Loop
// ----------------------------------------------------------------------------
void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus)
{
    if (!conn || !virtualStylus) return;

    int ret = 0;
    int transferred = 0;
    unsigned char acc_buf[512]; // Standard USB High-Speed Bulk packet size

    // Claim the AOA interface
    ret = libusb_claim_interface(conn->getHandle(), AOA_ACCESSORY_INTERFACE);
    if (ret != 0) {
        cerr << "Error claiming interface: " << libusb_error_name(ret) << endl;
        return;
    }

    cout << "Accessory interface claimed. Starting capture loop..." << endl;

    // Stack allocation for event data (Performance optimization)
    AccessoryEventData eventData;

    while (!InkBridge::stop_acc) {
        ret = libusb_bulk_transfer(conn->getHandle(),
                                   AOA_ACCESSORY_EP_IN,
                                   acc_buf,
                                   sizeof(acc_buf),
                                   &transferred,
                                   200); // 200ms timeout

        if (ret < 0) {
            if (ret == LIBUSB_ERROR_TIMEOUT) {
                continue; // Normal timeout, retry
            }
            cerr << "Bulk transfer error: " << libusb_error_name(ret) << endl;
            
            // If device was unplugged, exit loop
            if (ret == LIBUSB_ERROR_NO_DEVICE) break;
            
            break;
        }

        if (transferred == 0) continue;

        // --- Process Batched Packets ---
        int processed = 0;

        while (processed + (int)sizeof(PenPacket) <= transferred) {
            // zero-copy cast to our protocol struct
            const PenPacket* packet = reinterpret_cast<const PenPacket*>(acc_buf + processed);
            
            fillEventData(eventData, packet);

            if (MainWindow::isDebugMode) {
                // Throttle logs slightly or keep as is for deep debugging
                qDebug() << "Packet [ T:" << eventData.toolType 
                         << " A:" << eventData.action
                         << " P:" << eventData.pressure 
                         << "]";
            }

            virtualStylus->handleAccessoryEventData(&eventData);

            processed += sizeof(PenPacket);
        }
    }

    cout << "Capture loop finished." << endl;
}

// ----------------------------------------------------------------------------
// Legacy CSV Parser (Preserved)
// ----------------------------------------------------------------------------
bool parseAccessoryEventDataLine(const string &line, AccessoryEventData * accessoryEventData) {
    // Basic CSV parsing logic preserved for legacy file playback features
    size_t start = 0;
    size_t end = line.find(',');
    int index = 0;
    string parts[5];

    while (end != string::npos && index < 5) {
        parts[index++] = line.substr(start, end - start);
        start = end + 1;
        end = line.find(',', start);
    }
    if (index < 4) return false; // Need at least 4 parts
    parts[index] = line.substr(start); // Last part

    try {
        accessoryEventData->toolType = stoi(parts[0]);
        accessoryEventData->action = stoi(parts[1]);
        accessoryEventData->x = stoi(parts[2]);
        accessoryEventData->y = stoi(parts[3]);
        accessoryEventData->pressure = stof(parts[4]);
        return true;
    } catch (...) {
        return false;
    }
}