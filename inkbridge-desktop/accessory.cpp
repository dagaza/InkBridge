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
// fillEventData — unchanged
// ----------------------------------------------------------------------------
static void fillEventData(AccessoryEventData& data, const PenPacket* packet) {
    data.toolType = packet->toolType;
    data.action   = packet->action;
    data.x        = packet->x;
    data.y        = packet->y;
    data.pressure = static_cast<float>(packet->pressure) / 4096.0f;
    data.tiltX    = packet->tiltX;
    data.tiltY    = packet->tiltY;
}

// ----------------------------------------------------------------------------
// dispatchBuffer
//
// Parses as many complete packets as possible out of [buf, buf+len) and
// dispatches each one to the appropriate VirtualStylus handler.
//
// Returns the number of bytes successfully consumed. Any trailing bytes that
// don't form a complete packet must be re-prepended to the next transfer
// buffer by the caller (partial-packet handling).
//
// Wire format (see protocol.h):
//
//   [1B type=0x01] [22B PenPacket payload]        = 23 bytes total
//   [1B type=0x02] [1B fingerCount] [N×10B slots] = 2 + N*10 bytes total
//
// All other type bytes are treated as protocol errors and the loop is
// aborted for that transfer (we can't know the payload length to skip).
// ----------------------------------------------------------------------------
static int dispatchBuffer(const unsigned char* buf, int len,
                           VirtualStylus* virtualStylus,
                           AccessoryEventData& eventData,
                           int& lastAction, int& lastTool)
{
    int processed = 0;

    while (processed < len) {
        // Need at least the type byte.
        if (processed + 1 > len) break;

        uint8_t type = buf[processed];

        // Heartbeat packet: [0x03][0x00][0x00] = 3 bytes. Skip it entirely.
        if (type == PACKET_TYPE_HEARTBEAT) {
            processed += 3;
            continue;
        }

        if (type == PACKET_TYPE_PEN) {
            // ----------------------------------------------------------------
            // PEN PACKET: 1 type byte + 22 payload bytes = 23 bytes total.
            // ----------------------------------------------------------------
            constexpr int totalSize = 1 + static_cast<int>(sizeof(PenPacket));
            if (processed + totalSize > len) break; // wait for more data

            const PenPacket* packet =
                reinterpret_cast<const PenPacket*>(buf + processed + 1);
            fillEventData(eventData, packet);

            if (Backend::isDebugMode) {
                if (eventData.action != lastAction || eventData.toolType != lastTool) {
                    qDebug() << "--- PEN STATE CHANGE ---"
                             << "Action:" << eventData.action
                             << "Tool:"   << eventData.toolType;
                    lastAction = eventData.action;
                    lastTool   = eventData.toolType;
                }
            }

            virtualStylus->handleAccessoryEventData(&eventData);
            processed += totalSize;

        } else if (type == PACKET_TYPE_TOUCH) {
            // ----------------------------------------------------------------
            // TOUCH PACKET: 1 type byte + 1 fingerCount byte + N×10 bytes.
            // Minimum size (1 finger): 12 bytes.
            // ----------------------------------------------------------------
            // Need at least the fingerCount byte.
            if (processed + 2 > len) break;

            uint8_t fingerCount = buf[processed + 1];

            // Sanity-check: reject malformed packets with 0 or too many fingers.
            if (fingerCount == 0 || fingerCount > MT_MAX_SLOTS) {
                cerr << "MT: bad fingerCount=" << static_cast<int>(fingerCount)
                     << ", aborting parse for this transfer." << endl;
                processed = len; // skip remainder
                break;
            }

            const int totalSize = 2 + fingerCount * static_cast<int>(sizeof(TouchFingerSlot));
            if (processed + totalSize > len) break; // wait for more data

            const TouchFingerSlot* fingerSlots =
                reinterpret_cast<const TouchFingerSlot*>(buf + processed + 2);

            if (Backend::isDebugMode) {
                qDebug() << "--- TOUCH PACKET fingers:" << fingerCount << "---";
                for (int i = 0; i < fingerCount; ++i) {
                    qDebug() << "  slot" << fingerSlots[i].slotId
                             << "state" << fingerSlots[i].state
                             << "x" << fingerSlots[i].x
                             << "y" << fingerSlots[i].y;
                }
            }

            virtualStylus->handleTouchPacket(fingerSlots, fingerCount);
            processed += totalSize;

        } else {
            // ----------------------------------------------------------------
            // UNKNOWN TYPE — protocol error.
            // We can't determine the payload length so we cannot skip forward
            // safely. Abort processing for this USB transfer. The next bulk
            // read will re-synchronise if the stream recovers.
            // ----------------------------------------------------------------
            cerr << "MT: unknown packet type=0x"
                 << hex << static_cast<int>(type) << dec
                 << " at offset " << processed
                 << ", aborting parse." << endl;
            processed = len;
            break;
        }
    }

    return processed;
}

// ----------------------------------------------------------------------------
// accessory_main
//
// Main USB capture loop. The only structural change from the original is:
//   1. The inner parse loop now calls dispatchBuffer() instead of directly
//      casting to PenPacket.
//   2. A small leftover[] scratch buffer handles packets that straddle two
//      consecutive USB transfers (partial-packet carry-over).
//
// The 512-byte USB buffer is unchanged — PenPackets are 23 bytes and
// TouchPackets are 22 bytes for 2 fingers, so we comfortably batch ~22
// packets per transfer.
// ----------------------------------------------------------------------------
void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus)
{
    if (!conn || !virtualStylus) return;

    int ret         = 0;
    int transferred = 0;

    // Primary receive buffer.
    static constexpr int BUF_SIZE = 512;
    unsigned char acc_buf[BUF_SIZE];

    // Leftover scratch: holds at most one incomplete packet across transfers.
    // Max possible packet size: 2 + MT_MAX_SLOTS * sizeof(TouchFingerSlot)
    // = 2 + 10*10 = 102 bytes. Round up to 128 for a clean power-of-two.
    static constexpr int LEFTOVER_MAX = 128;
    unsigned char leftover[LEFTOVER_MAX];
    int           leftoverLen = 0;

    // Debug state trackers (pen path only — unchanged semantics).
    int lastAction = -1;
    int lastTool   = -1;

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

        // If there were leftover bytes from the previous transfer, prepend them
        // to form a contiguous parse buffer.
        const unsigned char* parsePtr = acc_buf;
        int                  parseLen = transferred;

        unsigned char combined[LEFTOVER_MAX + BUF_SIZE];
        if (leftoverLen > 0) {
            memcpy(combined, leftover, leftoverLen);
            memcpy(combined + leftoverLen, acc_buf, transferred);
            parsePtr  = combined;
            parseLen  = leftoverLen + transferred;
            leftoverLen = 0;
        }

        int consumed = dispatchBuffer(parsePtr, parseLen,
                                      virtualStylus, eventData,
                                      lastAction, lastTool);

        // Save any unconsumed trailing bytes as leftover for next iteration.
        int remaining = parseLen - consumed;
        if (remaining > 0 && remaining <= LEFTOVER_MAX) {
            memcpy(leftover, parsePtr + consumed, remaining);
            leftoverLen = remaining;
        } else if (remaining > LEFTOVER_MAX) {
            // Should never happen with well-formed packets and a 512-byte buffer,
            // but guard against it to avoid a buffer overrun.
            cerr << "MT: leftover too large (" << remaining
                 << " bytes), discarding." << endl;
            leftoverLen = 0;
        }
    }

    cout << "Capture loop finished." << endl;
}

// Legacy parser (unchanged, strictly for compilation)
bool parseAccessoryEventDataLine(const string& line, AccessoryEventData* accessoryEventData) {
    return false;
}