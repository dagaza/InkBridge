#ifndef ACCESSORY_H
#define ACCESSORY_H

#include <string>
#include <array>

// Forward declarations
class VirtualStylus;
namespace InkBridge {
    class UsbConnection;
}

struct AccessoryEventData {
    int toolType;
    int action;
    float pressure;
    int x;
    int y;
    // --- NEW FIELDS ---
    int tiltX;
    int tiltY;
};

void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus);
bool parseAccessoryEventDataLine(const std::string &line, AccessoryEventData * accessoryEventData);

#endif // ACCESSORY_H