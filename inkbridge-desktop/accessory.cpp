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

#include <stdio.h>
#include <string>
#include <string.h>
#include <stdlib.h>
#include <iostream>
#include <libusb-1.0/libusb.h>
#include <QDebug>
#include "mainwindow.h"
#include "virtualstylus.h"
#include "accessory.h"
#include "linux-adk.h"
#include "protocol.h"

// Compile-time check to ensure struct packing is correct on this platform
static_assert(sizeof(PenPacket) == 14, "FATAL ERROR: Struct packing failed! Size is not 14 bytes.");

using namespace std;

void extractAccessoryEventData(AccessoryEventData * accessoryEventData,
                                              unsigned char* dataBuffer, int size)
{
    // Note: The loop in accessory_main now handles the size check, but we keep this for safety
    if (size < (int)sizeof(PenPacket)) return;

    PenPacket* packet = reinterpret_cast<PenPacket*>(dataBuffer);
    
    // Debug output moved to main loop to avoid spamming for every sub-packet
    // unless strictly necessary. Keeping logic here if called individually.

    accessoryEventData->toolType = packet->toolType;
    accessoryEventData->action = packet->action;
    accessoryEventData->x = packet->x;
    accessoryEventData->y = packet->y;
    accessoryEventData->pressure = (float)packet->pressure / 1000.0f;
}

bool parseAccessoryEventDataLine(const string &line, AccessoryEventData * accessoryEventData){
    array<string, 5> parts;
    size_t start = 0;
    int index = 0;
    for(size_t i = 0; i < line.size() && index < 5; i++){
        if(line[i] == ','){
            parts[index] = line.substr(start, i - start);
            start = i + 1;
            index++;
        }
    }
    if(index < 5){
        return false;
    }

    char * endPtr = nullptr;
    long toolType = std::strtol(parts[0].c_str(), &endPtr, 10);
    if(endPtr == parts[0].c_str() || *endPtr != '\0'){
        return false;
    }
    long action = std::strtol(parts[1].c_str(), &endPtr, 10);
    if(endPtr == parts[1].c_str() || *endPtr != '\0'){
        return false;
    }
    long x = std::strtol(parts[2].c_str(), &endPtr, 10);
    if(endPtr == parts[2].c_str() || *endPtr != '\0'){
        return false;
    }
    long y = std::strtol(parts[3].c_str(), &endPtr, 10);
    if(endPtr == parts[3].c_str() || *endPtr != '\0'){
        return false;
    }
    double pressure = std::strtod(parts[4].c_str(), &endPtr);
    if(endPtr == parts[4].c_str()){
        return false;
    }

    accessoryEventData->toolType = static_cast<int>(toolType);
    accessoryEventData->action = static_cast<int>(action);
    accessoryEventData->x = static_cast<int>(x);
    accessoryEventData->y = static_cast<int>(y);
    accessoryEventData->pressure = static_cast<float>(pressure);
    return true;
}

void accessory_main(accessory_t * acc, VirtualStylus* virtualStylus)
{
    int ret = 0;
    /* If we have an accessory interface */
    if ((acc->pid != AOA_AUDIO_ADB_PID) && (acc->pid != AOA_AUDIO_PID)) {
        unsigned char acc_buf[512];
        int transferred;
        int errors = 20;

        /* Claiming first (accessory )interface from the opened device */
        ret = libusb_claim_interface(acc->handle, AOA_ACCESSORY_INTERFACE);
        if (ret != 0) {
            printf("Error %d claiming interface...\n", ret);
            return;
        }

        /* Snooping loop; Display every data received from device */
        AccessoryEventData * accessoryEventData = new AccessoryEventData();
        
        while (!stop_acc) {
            ret = libusb_bulk_transfer(acc->handle,
                         AOA_ACCESSORY_EP_IN, acc_buf,
                         sizeof(acc_buf), &transferred,
                         200);

            if (ret < 0) {
                if (ret == LIBUSB_ERROR_TIMEOUT)
                    continue;
                printf("bulk transfer error %d\n", ret);
                if (--errors == 0)
                    break;
                else
                    sleep(1);
            }

            // --- CHANGED: Loop through the buffer to handle batched events ---
            int processed = 0;
            int packetCount = 0; // Tracks the index within this specific USB transfer
            
            if(MainWindow::isDebugMode && transferred > 0){
                 // Optional: Print total bytes received for this block
                 // printf("Received %d bytes (%d packets total)\n", transferred, transferred / (int)sizeof(PenPacket));
            }

            while (processed + (int)sizeof(PenPacket) <= transferred) {
                // Point to the current 14-byte chunk in the buffer
                unsigned char* currentPayload = acc_buf + processed;

                // Parse this specific chunk into our data structure
                extractAccessoryEventData(accessoryEventData, currentPayload, sizeof(PenPacket));

                // Logging specific packet details with a clean index
                if (MainWindow::isDebugMode) {
                     qDebug() << "Packet [" << packetCount << "]:" 
                              << "X:" << accessoryEventData->x 
                              << "Y:" << accessoryEventData->y
                              << "P:" << accessoryEventData->pressure;
                }

                // Inject event into the virtual stylus driver
                virtualStylus->handleAccessoryEventData(accessoryEventData);

                // Advance to the next chunk and increment counter
                processed += sizeof(PenPacket);
                packetCount++;
            }
            // ----------------------------------------------------------------
        }
        delete accessoryEventData;
    }
}