#include "linux-adk.h"
#include <iostream>
#include <thread>
#include <chrono>
#include <cstring>
#include <csignal>

// External legacy function - We will need to refactor this next!
// Note: We changed the signature to accept our new C++ class
extern void accessory_main(InkBridge::UsbConnection* conn, VirtualStylus* virtualStylus);

namespace InkBridge {

// Global flag for signal handling
volatile std::atomic<bool> stop_acc{false};

void signal_handler(int) {
    std::cout << "SIGINT: Stopping accessory..." << std::endl;
    stop_acc = true;
}

UsbConnection::UsbConnection(Config cfg) : config(std::move(cfg)) {
    // Initialize LibUSB context (could be wrapped in a class too, but acceptable here)
    libusb_init(nullptr);
    // libusb_set_option(nullptr, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_INFO);
}

UsbConnection::~UsbConnection() {
    handle.reset(); // Closes device handle via LibUsbDeleter
    libusb_exit(nullptr);
}

int UsbConnection::startCapture(const std::string& selectedDevice, VirtualStylus* stylus) {
    // Update config with user selection
    config.deviceId = selectedDevice;

    if (std::signal(SIGINT, signal_handler) == SIG_ERR) {
        std::cerr << "Error: Cannot setup signal handler." << std::endl;
    }

    // On Windows, AOA 2.0 has issues, limiting to 1.0 (logic preserved from original)
    int maxVersion = -1;
#ifdef WIN32
    maxVersion = 1;
#endif

    if (initAccessory(maxVersion) != 0) {
        std::cerr << "Failed to initialize accessory." << std::endl;
        return -1;
    }

    // Call the main loop (defined in accessory.cpp)
    // We pass 'this' because we are the new "Accessory Context"
    accessory_main(this, stylus);

    return 0;
}

bool UsbConnection::isAccessoryPresent() {
    // Try opening known Google Accessory PIDs
    const uint16_t VID = 0x18D1;
    const uint16_t PIDS[] = {0x2D00, 0x2D01};

    for (uint16_t pid : PIDS) {
        libusb_device_handle* raw_handle = libusb_open_device_with_vid_pid(nullptr, VID, pid);
        if (raw_handle) {
            std::cout << "Found accessory " << std::hex << VID << ":" << pid << std::dec << std::endl;
            // Transfer ownership to unique_ptr
            handle.reset(raw_handle);
            return true;
        }
    }
    return false;
}

void UsbConnection::sendString(uint16_t index, const std::string& str) {
    if (str.empty()) return;

    std::cout << "Sending string ID " << index << ": " << str << std::endl;
    
    // +1 for null terminator
    int ret = libusb_control_transfer(handle.get(),
                                      LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_VENDOR,
                                      52, // AOA_SEND_IDENT
                                      0,
                                      index,
                                      (unsigned char*)str.c_str(),
                                      static_cast<uint16_t>(str.length() + 1),
                                      0);
    if (ret < 0) {
        throw std::runtime_error("Failed to send string: " + str);
    }
}

int UsbConnection::initAccessory(int maxAoaVersion) {
    using namespace std::chrono_literals;

    int ret;
    int tries = 10;

    // 1. Check if already in accessory mode
    if (isAccessoryPresent()) {
        return 0;
    }

    // 2. Parse VID:PID from string (e.g., "18d1:4ee2")
    size_t colonPos = config.deviceId.find(':');
    if (colonPos == std::string::npos) {
        std::cerr << "Invalid device format: " << config.deviceId << std::endl;
        return -1;
    }

    uint16_t vid = (uint16_t)std::stoi(config.deviceId.substr(0, colonPos), nullptr, 16);
    uint16_t pid = (uint16_t)std::stoi(config.deviceId.substr(colonPos + 1), nullptr, 16);

    std::cout << "Looking for device " << std::hex << vid << ":" << pid << std::dec << std::endl;

    // 3. Open generic device
    libusb_device_handle* raw_handle = libusb_open_device_with_vid_pid(nullptr, vid, pid);
    if (!raw_handle) {
        std::cerr << "Unable to open device." << std::endl;
        return -1;
    }
    // Temporary owner until we switch to accessory mode
    std::unique_ptr<libusb_device_handle, LibUsbDeleter> tempHandle(raw_handle);

    // 4. Check AOA Protocol Version
    unsigned char buffer[2];
    ret = libusb_control_transfer(tempHandle.get(),
                                  LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_VENDOR,
                                  51, // AOA_GET_PROTOCOL
                                  0, 0, buffer, sizeof(buffer), 0);
    
    if (ret < 0) {
        std::cerr << "Device does not support AOA." << std::endl;
        return ret;
    }

    aoaVersion = (buffer[1] << 8) | buffer[0];
    std::cout << "Device supports AOA " << aoaVersion << ".0" << std::endl;

    if (maxAoaVersion > 0 && aoaVersion > (uint32_t)maxAoaVersion) {
        aoaVersion = maxAoaVersion;
    }

    std::this_thread::sleep_for(10ms);

    // 5. Send Identification Strings
    try {
        // We use the raw handle here, helper function uses handle.get()
        // We temporarily move ownership to member variable for helper use
        handle = std::move(tempHandle); 
        
        sendString(0, config.manufacturer); // AOA_STRING_MAN_ID
        sendString(1, config.model);        // AOA_STRING_MOD_ID
        sendString(3, config.version);      // AOA_STRING_VER_ID
        
        // 6. Switch to Accessory Mode
        std::cout << "Requesting accessory mode switch..." << std::endl;
        ret = libusb_control_transfer(handle.get(),
                                      LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_VENDOR,
                                      53, // AOA_START_ACCESSORY
                                      0, 0, nullptr, 0, 0);
        if (ret < 0) throw std::runtime_error("Failed to start accessory mode");

        // Release handle (device will disconnect and reappear)
        handle.reset(); 
    } catch (const std::exception& e) {
        std::cerr << "Error during init: " << e.what() << std::endl;
        return -1;
    }

    // 7. Re-connect Loop
    std::this_thread::sleep_for(100ms); // Wait for re-enumeration
    while (tries--) {
        if (isAccessoryPresent()) {
            return 0; 
        }
        std::this_thread::sleep_for(1s);
    }

    std::cerr << "Timed out waiting for accessory to reappear." << std::endl;
    return -1;
}

} // namespace InkBridge