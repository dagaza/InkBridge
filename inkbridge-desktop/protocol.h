#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <cstdint>

// Ensure 1-byte alignment so the struct is exactly 14 bytes
#pragma pack(push, 1)

/**
 * @brief Binary packet structure for Pen events.
 * * Total size: 14 bytes.
 * Format: [1B toolType] [1B action] [4B x] [4B y] [4B pressure]
 * Endianness: Little Endian (as enforced by protocol)
 */
struct PenPacket {
    uint8_t toolType;
    uint8_t action;
    int32_t x;
    int32_t y;
    int32_t pressure;
};

#pragma pack(pop)

#endif // PROTOCOL_H