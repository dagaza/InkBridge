#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <cstdint>

// Ensure 1-byte alignment so the struct is exactly 22 bytes
#pragma pack(push, 1)

/**
 * @brief Binary packet structure for Pen events.
 * * Total size: 22 bytes.
 * Format:
 * [1B toolType] [1B action]
 * [4B x] [4B y] [4B pressure]
 * [4B tiltX] [4B tiltY]
 *
 * Endianness: Little Endian
 */
struct PenPacket {
    uint8_t toolType;
    uint8_t action;
    int32_t x;
    int32_t y;
    int32_t pressure;
    int32_t tiltX; // New: Tilt along X axis (Degrees)
    int32_t tiltY; // New: Tilt along Y axis (Degrees)
};

#pragma pack(pop)

#endif // PROTOCOL_H