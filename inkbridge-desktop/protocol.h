#ifndef PROTOCOL_H
#define PROTOCOL_H

#include <cstdint>

// ----------------------------------------------------------------------------
// PACKET TYPE FRAMING BYTE
//
// Every packet sent over the wire is now prefixed with a 1-byte type tag.
// This allows the desktop receive loop to dispatch to the correct handler
// without relying on packet size heuristics.
//
// PACKET_TYPE_PEN   (0x01) — followed by PenPacket   (22 bytes) = 23 bytes total
// PACKET_TYPE_TOUCH (0x02) — followed by TouchHeader (2 bytes) +
//                            fingerCount × TouchFingerSlot (10 bytes each)
//
// For a 2-finger pinch: 1 + 2 + 2*10 = 23 bytes total (same wire cost as pen).
// ----------------------------------------------------------------------------
static constexpr uint8_t PACKET_TYPE_PEN       = 0x01;
static constexpr uint8_t PACKET_TYPE_TOUCH     = 0x02;
static constexpr uint8_t PACKET_TYPE_HEARTBEAT = 0x03;  // 3-byte keep-alive, no payload

// Maximum simultaneous touch points we support. Android's MT protocol
// supports up to 10 but 2 is sufficient for pinch/pan. Raising this limit
// only affects the desktop slot table; no protocol change is needed.
static constexpr int MT_MAX_SLOTS = 10;

// ----------------------------------------------------------------------------
// Ensure 1-byte alignment so structs are exactly the documented sizes.
// ----------------------------------------------------------------------------
#pragma pack(push, 1)

// ----------------------------------------------------------------------------
// PenPacket — type byte 0x01
//
// Unchanged from the original 22-byte layout. The type byte is prepended
// by the Android sender and stripped by the desktop dispatcher before this
// struct is parsed, so the struct itself stays at 22 bytes.
//
// Total size: 22 bytes.
// [1B toolType][1B action][4B x][4B y][4B pressure][4B tiltX][4B tiltY]
// Endianness: Little Endian
// ----------------------------------------------------------------------------
struct PenPacket {
    uint8_t toolType;
    uint8_t action;
    int32_t x;
    int32_t y;
    int32_t pressure;
    int32_t tiltX;
    int32_t tiltY;
};

// ----------------------------------------------------------------------------
// TouchFingerSlot — one entry in a TouchPacket's finger array.
//
// Size: 10 bytes.
// [1B slotId] [1B state] [4B x] [4B y]
//
// slotId : Stable Android pointerId (0–9). Maps 1:1 to a Linux MT slot.
//          Must remain the same value for a finger's entire contact lifetime.
// state  : 1 = finger down/moving, 0 = finger lifted.
//          A lifted slot carries its last known XY for convenience; the
//          desktop sets ABS_MT_TRACKING_ID = -1 and ignores the XY.
// x, y   : Normalised 0–32767, same coordinate space as PenPacket.
// ----------------------------------------------------------------------------
struct TouchFingerSlot {
    uint8_t slotId;
    uint8_t state;   // 1 = active, 0 = lifted
    int32_t x;
    int32_t y;
};

// ----------------------------------------------------------------------------
// TouchPacket — type byte 0x02
//
// Variable-length: the header is 2 bytes, followed by fingerCount slots.
// Total wire size for N fingers: 2 + N * sizeof(TouchFingerSlot) = 2 + N*10.
//
// The desktop reads the header first (2 bytes), then reads exactly
// fingerCount * sizeof(TouchFingerSlot) additional bytes.
//
// fingerCount must be in the range [1, MT_MAX_SLOTS].
//
// This struct represents the fixed header only. The finger array is laid out
// contiguously in memory immediately after it on the wire; the desktop
// accesses it via a pointer cast (see accessory.cpp dispatch logic).
// ----------------------------------------------------------------------------
struct TouchPacketHeader {
    uint8_t packetType;    // Always PACKET_TYPE_TOUCH (0x02) — included here
                           // so the full wire packet can be cast as one region.
    uint8_t fingerCount;   // Number of TouchFingerSlot entries that follow.
};

#pragma pack(pop)

// Compile-time size assertions — catch any accidental padding immediately.
static_assert(sizeof(PenPacket)        == 22, "PenPacket must be 22 bytes");
static_assert(sizeof(TouchFingerSlot)  == 10, "TouchFingerSlot must be 10 bytes");
static_assert(sizeof(TouchPacketHeader) == 2, "TouchPacketHeader must be 2 bytes");

#endif // PROTOCOL_H