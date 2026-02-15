#ifndef ACCESSORY_H
#define ACCESSORY_H

#include <string>
#include <array>
#include <atomic> // <--- THIS WAS MISSING. REQUIRED FOR std::atomic

// Forward declarations
class VirtualStylus;

// We need to forward declare UsbConnection properly since it's inside a namespace
namespace InkBridge {
    class UsbConnection;
    
    // This variable controls the main loop in accessory.cpp
    // 'extern' tells the compiler "this exists, but is defined in the .cpp file"
    extern volatile std::atomic<bool> stop_acc;
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

// Function prototypes
void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus);
bool parseAccessoryEventDataLine(const std::string &line, AccessoryEventData * accessoryEventData);

#endif // ACCESSORY_H