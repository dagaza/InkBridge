#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <cstdint>

/**
 * @brief Binary packet structure for Pen events.
 * 
 * Total size: 14 bytes.
 * Format: [1B toolType] [1B action] [4B x] [4B y] [4B pressure]
 * Endianness: Little Endian (as enforced by protocol)
 */
struct __attribute__((packed)) PenPacket {
    uint8_t toolType;
    uint8_t action;
    int32_t x;
    int32_t y;
    int32_t pressure;
};

#endif // PROTOCOL_H
