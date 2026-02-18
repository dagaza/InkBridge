#ifndef LINUX_ADK_H
#define LINUX_ADK_H

#include <string>
#include <memory>
#include <libusb-1.0/libusb.h>
#include "virtualstylus.h"

namespace InkBridge {

// Forward declaration
class UsbConnection;

// Moved OUTSIDE the class to fix GCC build error
struct UsbConnectionConfig {
    std::string deviceId = "18d1:4ee2";
    std::string manufacturer = "dzadobrischi";
    std::string model = "InkBridgeHost";
    std::string description = "InkBridge Desktop Client";
    std::string version = "1.0.0";
    std::string url = "https://github.com/dagaza/InkBridge";
    std::string serial = "INKBRIDGE001";
};

/**
 * @brief Manages the Low-Level USB AOA negotiation.
 */
class UsbConnection {
public:
    // Alias to keep existing code working
    using Config = UsbConnectionConfig;

    UsbConnection(Config config = Config{});
    ~UsbConnection();

    // Disable copying
    UsbConnection(const UsbConnection&) = delete;
    UsbConnection& operator=(const UsbConnection&) = delete;

    int startCapture(const std::string& deviceId, VirtualStylus* stylus);
    libusb_device_handle* getHandle() const { return handle.get(); }

    // --- MOVED TO PUBLIC ---
    bool isAccessoryPresent();
    // -----------------------

private:
    struct LibUsbDeleter {
        void operator()(libusb_device_handle* h) const {
            if (h) {
                libusb_release_interface(h, 0);
                libusb_close(h);
            }
        }
    };

    std::unique_ptr<libusb_device_handle, LibUsbDeleter> handle;
    Config config;
    uint32_t aoaVersion = 0;

    int initAccessory(int maxAoaVersion);
    void sendString(uint16_t index, const std::string& str);
};

} // namespace InkBridge

#endif // LINUX_ADK_H