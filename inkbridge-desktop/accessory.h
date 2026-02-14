#ifndef ACCESSORY_H
#define ACCESSORY_H

#include <string>
#include <array>

// Forward declarations to avoid circular includes
class VirtualStylus;
namespace InkBridge {
    class UsbConnection;
}

// Modern C++ Struct
struct AccessoryEventData {
    int toolType;
    int action;
    float pressure;
    int x;
    int y;
};

// Main Capture Loop (Now accepts our new UsbConnection class)
void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus);

// Legacy CSV Parser (Kept for backward compatibility if needed)
bool parseAccessoryEventDataLine(const std::string &line, AccessoryEventData * accessoryEventData);

#endif // ACCESSORY_H