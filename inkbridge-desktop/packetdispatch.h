#ifndef PACKETDISPATCH_H
#define PACKETDISPATCH_H

// ----------------------------------------------------------------------------
// packetdispatch.h
//
// Shared inline helper for decoding the framed binary protocol and routing
// packets to VirtualStylus. Used by all three transport backends:
//   • accessory.cpp   (USB / AOA)
//   • wifidirectserver.cpp
//   • bluetoothserver.cpp
//
// Having a single implementation here avoids three diverging copies of the
// same parsing logic. Any protocol change only needs to be made once.
// ----------------------------------------------------------------------------

#include "protocol.h"
#include "virtualstylus.h"
#include "accessory.h"    // AccessoryEventData, fillEventDataFromPacket
#include <cstring>
#include <iostream>

// fillEventData is defined in accessory.cpp; forward-declare it here so the
// WiFi and Bluetooth servers can call it without duplicating it.
//
// If you later move fillEventData to a shared translation unit, remove this
// declaration and #include that header instead.
static inline void fillEventDataFromPacket(AccessoryEventData& data,
                                            const PenPacket* packet) {
    data.toolType = packet->toolType;
    data.action   = packet->action;
    data.x        = packet->x;
    data.y        = packet->y;
    data.pressure = static_cast<float>(packet->pressure) / 4096.0f;
    data.tiltX    = packet->tiltX;
    data.tiltY    = packet->tiltY;
}

// ----------------------------------------------------------------------------
// dispatchPacketBuffer
//
// Identical dispatch logic to accessory.cpp's dispatchBuffer(), extracted
// here so all three transports share the same implementation.
//
// Parameters:
//   buf           — pointer to the start of the received data
//   len           — number of valid bytes in buf
//   virtualStylus — target for decoded events
//   eventData     — caller-owned scratch struct (avoids per-call allocation)
//
// Returns the number of bytes consumed. Any remaining bytes (incomplete
// trailing packet) must be carried over to the next receive call.
// ----------------------------------------------------------------------------
inline int dispatchPacketBuffer(const unsigned char* buf, int len,
                                 VirtualStylus*       virtualStylus,
                                 AccessoryEventData&  eventData)
{
    int processed = 0;

    while (processed < len) {
        if (processed + 1 > len) break;

        uint8_t type = buf[processed];

        // Heartbeat packet: [0x03][0x00][0x00] = 3 bytes. Skip it entirely.
        if (type == PACKET_TYPE_HEARTBEAT) {
            processed += 3;
            continue;
        }

        if (type == PACKET_TYPE_PEN) {
            constexpr int totalSize = 1 + static_cast<int>(sizeof(PenPacket));
            if (processed + totalSize > len) break;

            const PenPacket* packet =
                reinterpret_cast<const PenPacket*>(buf + processed + 1);
            fillEventDataFromPacket(eventData, packet);
            virtualStylus->handleAccessoryEventData(&eventData);
            processed += totalSize;

        } else if (type == PACKET_TYPE_TOUCH) {
            {
                if (processed + 2 > len) break;

                uint8_t fingerCount = buf[processed + 1];
                if (fingerCount == 0 || fingerCount > MT_MAX_SLOTS) {
                    std::cerr << "dispatchPacketBuffer: bad fingerCount="
                              << static_cast<int>(fingerCount) << std::endl;
                    processed = len;
                    break;
                }

                const int totalSize =
                    2 + fingerCount * static_cast<int>(sizeof(TouchFingerSlot));
                if (processed + totalSize > len) break;

                const TouchFingerSlot* fingerSlots =
                    reinterpret_cast<const TouchFingerSlot*>(buf + processed + 2);
                virtualStylus->handleTouchPacket(fingerSlots, fingerCount);
                processed += totalSize;
            }

        } else {
            std::cerr << "dispatchPacketBuffer: unknown type=0x"
                      << std::hex << static_cast<int>(type) << std::dec
                      << " at offset " << processed << std::endl;
            processed = len;
            break;
        }
    }

    return processed;
}

#endif // PACKETDISPATCH_H