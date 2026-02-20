#include "backend.h"
#include "linux-adk.h"
#include <libusb-1.0/libusb.h>
#include <iostream> // For std::cout if needed, though qDebug is preferred for Qt
#include "protocol.h"       // For PenPacket struct
#include "accessory.h"      // For AccessoryEventData struct
#include "packetdispatch.h" // For dispatchPacketBuffer() — shared framed-protocol parser
// AOA Protocol Constants
#define AOA_GET_PROTOCOL    51
#define AOA_SEND_STRING     52
#define AOA_START           53

// Request Types
#define AOA_READ_TYPE   (LIBUSB_ENDPOINT_IN  | LIBUSB_REQUEST_TYPE_VENDOR | LIBUSB_RECIPIENT_DEVICE)
#define AOA_WRITE_TYPE  (LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_VENDOR | LIBUSB_RECIPIENT_DEVICE)

bool Backend::isDebugMode = false;

Backend::Backend(QObject *parent) 
    : QObject(parent)
    , m_status("Ready")     
    , m_connected(false)
    , m_wifiDirectRunning(false) 
    , m_pressureSensitivity(50)
    , m_minPressure(0)
    , m_swapAxis(false)
    , m_autoScanRunning(false) // Initialize flag
    , m_bluetoothRunning(false)
    , m_screenSelected(false)
{
    m_displayTranslator = new DisplayScreenTranslator();
    m_pressureTranslator = new PressureTranslator();
    m_stylus = new VirtualStylus(m_displayTranslator, m_pressureTranslator);
    
    
    m_wifiDirectServer = new WifiDirectServer(this);

    connect(m_wifiDirectServer, &WifiDirectServer::clientConnected,
            this, [this](QString ip) {
        m_stylus->initializeMTDevice();
        if (!m_screenSelected && !m_screenRects.isEmpty())
            selectScreen(0);
        updateStatus("Connected via WiFi Direct (" + ip + ")", true);
    });
    connect(m_wifiDirectServer, &WifiDirectServer::clientDisconnected,
            this, [this]() {
        m_stylus->destroyMTDevice();
        updateStatus("WiFi Direct: Waiting for tablet...", false);
    });
    connect(m_wifiDirectServer, &WifiDirectServer::dataReceived,
            this, &Backend::handleWifiDirectData);
    connect(m_wifiDirectServer, &WifiDirectServer::serverError,
            this, [this](QString msg) {
        updateStatus("WiFi Direct Error: " + msg, false);
        m_wifiDirectRunning = false;
        emit wifiDirectStatusChanged();
    });
    connect(m_wifiDirectServer, &WifiDirectServer::statusChanged,
            this, [this](QString msg) {
        // Forward status messages to the UI without changing isConnected.
        m_status = msg;
        emit connectionStatusChanged();
    });

    m_stylus->initializeStylus();

    m_bluetoothServer = new BluetoothServer(this);

    connect(m_bluetoothServer, &BluetoothServer::clientConnected,
            this, [this](QString address) {
        qDebug() << "[BT] Client connected from" << address;
        m_stylus->initializeMTDevice();
        if (!m_screenSelected && !m_screenRects.isEmpty())
            selectScreen(0);
        updateStatus("Connected via Bluetooth (" + address + ")", true);
    });

    connect(m_bluetoothServer, &BluetoothServer::clientDisconnected,
            this, [this]() {
        qDebug() << "[BT] Client disconnected";
        m_stylus->destroyMTDevice();
        updateStatus("Bluetooth Listening...", false);
    });

    connect(m_bluetoothServer, &BluetoothServer::dataReceived,
            this, &Backend::handleBluetoothData);

    connect(m_bluetoothServer, &BluetoothServer::serverError,
            this, [this](QString msg) {
        qDebug() << "[BT] Server error:" << msg;
        updateStatus("Bluetooth Error: " + msg, false);
        m_bluetoothRunning = false;
        emit bluetoothStatusChanged();
    });

    refreshScreens();

    // OPTIONAL: Start scanning immediately on launch
    startAutoConnect(); 

    // FORCE DEBUG ON
    Backend::isDebugMode = false; // <--- Add
}

Backend::~Backend() {
    stopAutoConnect(); // Stop thread safely
    if (m_bluetoothServer) {
        m_bluetoothServer->stopServer();
    }

    if (m_wifiDirectServer) {
        m_wifiDirectServer->stopServer();
    }

    delete m_stylus;
    delete m_displayTranslator;
    delete m_pressureTranslator;
}

// --- Getters ---
QStringList Backend::screenList() const { return m_screenNames; }
QVariantList Backend::screenGeometries() const { return m_screenGeometriesVariant; }
QString Backend::connectionStatus() const { return m_status; }
bool Backend::isConnected() const { return m_connected; }
QStringList Backend::usbDevices() const { return m_usbDeviceNames; }
bool Backend::isWifiDirectRunning() const { return m_wifiDirectRunning; }
bool Backend::isBluetoothRunning() const { return m_bluetoothRunning; }
int Backend::pressureSensitivity() const { return m_pressureSensitivity; }
int Backend::minPressure() const { return m_minPressure; }
bool Backend::swapAxis() const { return m_swapAxis; }

// --- Logic ---
void Backend::refreshScreens() {
    m_screenNames.clear();
    m_screenRects.clear();
    m_screenGeometriesVariant.clear();
    QRect totalRect;

    const auto screens = QGuiApplication::screens();
    int i = 1;
    for (QScreen *screen : screens) {
        QRect geom = screen->geometry();
        m_screenRects.append(geom);
        
        // Populate QML friendly map
        QVariantMap map;
        map["x"] = geom.x();
        map["y"] = geom.y();
        map["width"] = geom.width();
        map["height"] = geom.height();
        map["name"] = QString::number(i);
        m_screenGeometriesVariant.append(map);
        
        m_screenNames.append(QString("Screen %1: %2 (%3x%4)")
                             .arg(i++)
                             .arg(screen->name())
                             .arg(geom.width())
                             .arg(geom.height()));
        
        totalRect = totalRect.united(geom);
    }
    m_stylus->setTotalDesktopGeometry(totalRect);
    emit screenListChanged();
}

void Backend::selectScreen(int index) {
    if (index >= 0 && index < m_screenRects.size()) {
        m_stylus->setTargetScreen(m_screenRects[index]);
        m_screenSelected = true;
        qDebug() << "Selected Screen Index:" << index;
    }
}

void Backend::refreshUsbDevices() {
    m_usbDeviceNames.clear();
    m_usbDeviceIds.clear();

    libusb_context *ctx = nullptr;
    libusb_device **devs = nullptr;
    
    if (libusb_init(&ctx) < 0) return;
    
    ssize_t cnt = libusb_get_device_list(ctx, &devs);
    if (cnt < 0) { libusb_exit(ctx); return; }

    for (ssize_t i = 0; i < cnt; i++) {
        libusb_device *dev = devs[i];
        struct libusb_device_descriptor desc;
        if (libusb_get_device_descriptor(dev, &desc) < 0) continue;

        char idStr[10];
        sprintf(idStr, "%04x:%04x", desc.idVendor, desc.idProduct);
        
        libusb_device_handle *handle = nullptr;
        unsigned char product[256] = "USB Device";
        if (libusb_open(dev, &handle) == 0) {
            if (desc.iProduct) {
                libusb_get_string_descriptor_ascii(handle, desc.iProduct, product, sizeof(product));
            }
            libusb_close(handle);
        }
        
        m_usbDeviceNames.append(QString("%1 [%2]").arg((char*)product).arg(idStr));
        m_usbDeviceIds.append(QString(idStr));
    }
    
    libusb_free_device_list(devs, 1);
    libusb_exit(ctx);
    emit usbDevicesChanged();
}

void Backend::connectDevice(int deviceIndex) {
    if (deviceIndex < 0 || deviceIndex >= m_usbDeviceIds.size()) {
        updateStatus("Invalid Device Selected", false);
        return;
    }
    QString deviceId = m_usbDeviceIds[deviceIndex];
    updateStatus("Connecting...", true); 
    
    InkBridge::stop_acc = false;

    QtConcurrent::run([=, this](){
        InkBridge::UsbConnection connection;
        int res = connection.startCapture(deviceId.toStdString(), m_stylus);
        QMetaObject::invokeMethod(this, [=, this](){
            updateStatus("Disconnected (Code " + QString::number(res) + ")", false);
        });
    });
}

void Backend::disconnectDevice() {
    InkBridge::stop_acc = true;
    updateStatus("Disconnecting...", false);
}

// --- Restored Features ---

void Backend::setPressureSensitivity(int value) {
    if (m_pressureSensitivity != value) {
        m_pressureSensitivity = value;
        
        // --- THE FIX: Update the translator ---
        if (m_pressureTranslator) {
            m_pressureTranslator->sensitivity = value;
        }
        
        emit settingsChanged(); 
    }
}

void Backend::setMinPressure(int value) {
    if (m_minPressure != value) {
        m_minPressure = value;
        
        // --- THE FIX: Update the translator ---
        if (m_pressureTranslator) {
            m_pressureTranslator->minPressure = value;
        }
        
        emit settingsChanged();
    }
}

void Backend::setSwapAxis(bool swap) {
    m_swapAxis = swap;
    m_stylus->swapAxis = swap;
    emit settingsChanged();
}

void Backend::toggleWifiDirect() {
    m_wifiDirectRunning = !m_wifiDirectRunning;

    if (m_wifiDirectRunning) {
        bool started = m_wifiDirectServer->startServer();
        if (started) {
            updateStatus("WiFi Direct: Waiting for Android beacon...", false);
        } else {
            m_wifiDirectRunning = false;
        }
    } else {
        m_wifiDirectServer->stopServer();
        updateStatus("WiFi Direct Stopped", false);
    }

    emit wifiDirectStatusChanged();
}

void Backend::toggleBluetooth() {
    m_bluetoothRunning = !m_bluetoothRunning;

    if (m_bluetoothRunning) {
        bool started = m_bluetoothServer->startServer();
        if (started) {
            updateStatus("Bluetooth Listening (Waiting for Tablet...)", false);
        } else {
            // startServer() already emitted serverError with the reason.
            m_bluetoothRunning = false;
        }
    } else {
        m_bluetoothServer->stopServer();
        updateStatus("Bluetooth Stopped", false);
    }

    emit bluetoothStatusChanged();
}

void Backend::handleWifiDirectData(QByteArray data) {
    if (data.isEmpty() || !m_stylus) return;

    if (Backend::isDebugMode)
        qDebug() << "[P2P] Received" << data.size() << "bytes";

    // Prepend any bytes left over from the previous call (partial packet).
    if (!m_wifiLeftover.isEmpty()) {
        data.prepend(m_wifiLeftover);
        m_wifiLeftover.clear();
    }

    AccessoryEventData eventData;
    int consumed = dispatchPacketBuffer(
        reinterpret_cast<const unsigned char*>(data.constData()),
        data.size(),
        m_stylus,
        eventData);

    // Save any trailing bytes that didn't form a complete packet.
    if (consumed < data.size())
        m_wifiLeftover = data.mid(consumed);
}

void Backend::handleBluetoothData(QByteArray data) {
    if (data.isEmpty() || !m_stylus) return;

    if (Backend::isDebugMode)
        qDebug() << "[BT] Received" << data.size() << "bytes";

    // Prepend any bytes left over from the previous call (partial packet).
    if (!m_btLeftover.isEmpty()) {
        data.prepend(m_btLeftover);
        m_btLeftover.clear();
    }

    // Heartbeat detection: the Android client sends all-127 bytes during idle
    // to keep the RFCOMM connection alive. A heartbeat is exactly one framed
    // packet in length (23 bytes for a pen packet) with every byte == 127.
    // We check before dispatching so the dispatcher never sees them.
    // The framed size is 23 bytes (1 type + 22 payload); 127 is not a valid
    // packet type (only 0x01 and 0x02 are), so even if heartbeat detection
    // misses, the dispatcher will log an unknown-type warning and skip it
    // harmlessly.
    {
        constexpr int heartbeatSize = 1 + static_cast<int>(sizeof(PenPacket)); // 23
        if (data.size() == heartbeatSize) {
            bool isHeartbeat = true;
            const uint8_t* raw = reinterpret_cast<const uint8_t*>(data.constData());
            for (int i = 0; i < heartbeatSize; ++i) {
                if (raw[i] != 127) { isHeartbeat = false; break; }
            }
            if (isHeartbeat) {
                if (Backend::isDebugMode) qDebug() << "[BT] Heartbeat received, ignoring.";
                return;
            }
        }
    }

    AccessoryEventData eventData;
    int consumed = dispatchPacketBuffer(
        reinterpret_cast<const unsigned char*>(data.constData()),
        data.size(),
        m_stylus,
        eventData);

    // Save any trailing bytes that didn't form a complete packet.
    if (consumed < data.size())
        m_btLeftover = data.mid(consumed);
}

void Backend::toggleDebug(bool enable) {
    Backend::isDebugMode = enable;
    qDebug() << "Debug Mode:" << enable;
}

void Backend::resetDefaults() {
    m_pressureSensitivity = 50;
    m_minPressure = 0;
    setSwapAxis(false); // Helper handles bool update
    emit settingsChanged();
    qDebug() << "Defaults Reset";
}

void Backend::updateStatus(QString msg, bool connected) {
    m_status = msg;
    m_connected = connected;
    emit connectionStatusChanged();
    emit isConnectedChanged();
}

// --- NEW: Auto-Connect Implementation ---

void Backend::startAutoConnect() {
    if (m_autoScanRunning) {
        qDebug() << "[AutoConnect] Already running. Ignoring start request.";
        return;
    }
    
    qDebug() << "[AutoConnect] Starting background service...";
    m_autoScanRunning = true;
    updateStatus("Scanning for tablet...", false);
    
    m_autoScanThread = std::thread(&Backend::autoConnectLoop, this);
}

void Backend::stopAutoConnect() {
    qDebug() << "[AutoConnect] Stopping background service...";
    m_autoScanRunning = false;
    InkBridge::stop_acc = true; // Break the blocking capture loop
    
    if (m_autoScanThread.joinable()) {
        m_autoScanThread.join();
        qDebug() << "[AutoConnect] Thread joined and stopped.";
    }
}

bool Backend::trySwitchToAccessoryMode(libusb_device *dev, libusb_device_handle *handle) {
            uint16_t protocolVer = 0;
            
            // 1. Check if device supports AOA
            int res = libusb_control_transfer(handle, AOA_READ_TYPE, AOA_GET_PROTOCOL, 0, 0, (unsigned char*)&protocolVer, 2, 1000);
            
            // If error or version < 1, it's not an AOA-compatible device
            if (res < 0 || protocolVer < 1) return false;

            qDebug() << "[AutoConnect] Found Android Device (Protocol v" << protocolVer << "). Switching...";

            // 2. Define your strings (Must match what's in your Android App's accessory_filter.xml if you have one, 
            //    or simply match the Config struct in linux-adk.h)
            const char* manuf = "dzadobrischi";
            const char* model = "InkBridgeHost";
            const char* desc  = "InkBridge Desktop Client";
            const char* ver   = "1.0";
            const char* url   = "https://github.com/dagaza/InkBridge";
            const char* serial= "INKBRIDGE001";

            // 3. Send Strings (Index 0-5)
            auto sendString = [&](uint16_t idx, const char* str) {
                libusb_control_transfer(handle, AOA_WRITE_TYPE, AOA_SEND_STRING, 0, idx, (unsigned char*)str, strlen(str) + 1, 1000);
            };

            sendString(0, manuf);
            sendString(1, model);
            sendString(2, desc);
            sendString(3, ver);
            sendString(4, url);
            sendString(5, serial);

            // 4. Send Start Command (This causes the device to disconnect and reappear as 0x18D1)
            libusb_control_transfer(handle, AOA_WRITE_TYPE, AOA_START, 0, 0, nullptr, 0, 1000);
            
            return true; // We successfully sent the switch command
        }

void Backend::autoConnectLoop() {
    qDebug() << "[AutoConnect] Thread started. Loop entering...";

    while (m_autoScanRunning) {
        
        // 1. Scan
        qDebug() << "[AutoConnect] Scanning USB bus..."; // Comment out to reduce spam if too frequent
        QString deviceId = scanForInkBridgeDevice();

        if (!deviceId.isEmpty()) {
            qDebug() << "[AutoConnect] >>> DEVICE FOUND: " << deviceId;
            
            // 2. Connect
            QMetaObject::invokeMethod(this, [this](){
                m_stylus->initializeMTDevice();
                if (!m_screenSelected && !m_screenRects.isEmpty())
                    selectScreen(0);
                updateStatus("Tablet found! Connecting...", true);
            });

            InkBridge::stop_acc = false; 
            InkBridge::UsbConnection connection;
            
            qDebug() << "[AutoConnect] Engaging Capture Mode (Blocking)...";
            QMetaObject::invokeMethod(this, [this](){
                m_stylus->initializeMTDevice();
            });
            // This line blocks until the device is unplugged or error occurs
            int res = connection.startCapture(deviceId.toStdString(), m_stylus);

            qDebug() << "[AutoConnect] <<< DISCONNECTED. Return Code:" << res;
            QMetaObject::invokeMethod(this, [this](){
                m_stylus->destroyMTDevice();
            });

            // 3. Handle Disconnect
            QMetaObject::invokeMethod(this, [this, res](){
                updateStatus("Disconnected (Code " + QString::number(res) + "). Scanning...", false);
            });
            
            // Cooldown to prevent crazy loops
            std::this_thread::sleep_for(std::chrono::seconds(1));

        } else {
            // 4. Wait
            // No device found, sleep for 1s
            std::this_thread::sleep_for(std::chrono::seconds(1));
        }
    }
    qDebug() << "[AutoConnect] Loop exited.";
}

void Backend::forceUsbReset() {
    qDebug() << "[Backend] User requested Manual USB Reset.";
    
    // 1. Tell the background loop to stop capturing momentarily
    InkBridge::stop_acc = true; 
    
    // 2. Find the device again to get a fresh handle for resetting
    // We reuse the scanning logic essentially, but just to find the VID/PID
    libusb_context *ctx = nullptr;
    if (libusb_init(&ctx) < 0) return;

    libusb_device **devs = nullptr;
    ssize_t cnt = libusb_get_device_list(ctx, &devs);
    
    for (ssize_t i = 0; i < cnt; i++) {
        libusb_device *dev = devs[i];
        struct libusb_device_descriptor desc;
        if (libusb_get_device_descriptor(dev, &desc) < 0) continue;

        // Check for Google Accessory ID (The one we are currently connected to)
        if (desc.idVendor == 0x18d1 && (desc.idProduct == 0x2d00 || desc.idProduct == 0x2d01)) {
             
             // Open it momentarily
             libusb_device_handle *handle = nullptr;
             if (libusb_open(dev, &handle) == 0) {
                 qDebug() << "[Backend] Resetting Device: " << Qt::hex << desc.idVendor << ":" << desc.idProduct;
                 
                 // THE MAGIC COMMAND: Simulates a physical unplug
                 libusb_reset_device(handle);
                 
                 libusb_close(handle);
                 break; // Done
             }
        }
    }

    libusb_free_device_list(devs, 1);
    libusb_exit(ctx);
    
    // 3. Reset the stop flag so the loop can reconnect naturally
    // We give it a small delay so the loop in the other thread has time to fail and reset
    QTimer::singleShot(2000, this, [this](){
        InkBridge::stop_acc = false;
    });
}

QString Backend::scanForInkBridgeDevice() {
    libusb_context *ctx = nullptr;
    libusb_device **devs = nullptr;
    QString foundId = "";

    // 1. Initialize LibUSB
    if (libusb_init(&ctx) < 0) {
        qCritical() << "[AutoConnect] libusb_init failed!";
        return "";
    }

    // 2. Get Device List
    ssize_t cnt = libusb_get_device_list(ctx, &devs);
    if (cnt < 0) { 
        libusb_exit(ctx); 
        return ""; 
    }

    for (ssize_t i = 0; i < cnt; i++) {
        libusb_device *dev = devs[i];
        struct libusb_device_descriptor desc;
        if (libusb_get_device_descriptor(dev, &desc) < 0) continue;

        // ======================================================
        // PATH A: The "Happy Path" (Already Connected)
        // ======================================================
        // If the device is ALREADY a Google Accessory (0x18D1), we are done.
        // We catch PIDs: 0x2D00 (Accessory), 0x2D01 (Accessory + ADB)
        if (desc.idVendor == 0x18d1 && (desc.idProduct == 0x2d00 || desc.idProduct == 0x2d01)) {
             char idStr[16]; // Increased buffer size for safety
             snprintf(idStr, sizeof(idStr), "%04x:%04x", desc.idVendor, desc.idProduct);
             foundId = QString(idStr);
             
             qDebug() << "[AutoConnect] Found active accessory:" << foundId;
             break; // Stop scanning immediately
        }

        // ======================================================
        // PATH B: The "Wake Up" Path (Needs Handshake)
        // ======================================================
        
        // FILTER 1: Skip Hubs (Class 0x09)
        if (desc.bDeviceClass == 0x09) continue; 

        // FILTER 2: ONLY open supported brands (Samsung, Google, etc.)
        // Do NOT open mice, keyboards, or webcams!
        // Add your supported Vendor IDs here.
        bool isSupportedVendor = (desc.idVendor == 0x18d1 || // Google
                                  desc.idVendor == 0x04e8 || // Samsung
                                  desc.idVendor == 0x2717 || // Xiaomi
                                  desc.idVendor == 0x22b8 || // Motorola
                                  desc.idVendor == 0x12d1);  // Huawei
        
        if (!isSupportedVendor) continue;

        // Now it is safe to open and check
        libusb_device_handle *handle = nullptr;
        int err = libusb_open(dev, &handle);
        if (err == 0) {
            // Attempt to switch (Handshake)
            // If successful, the device will disconnect and reappear as Path A in ~2 seconds.
            bool switched = trySwitchToAccessoryMode(dev, handle);
            
            libusb_close(handle);
            
            if (switched) {
                qDebug() << "[AutoConnect] Handshake sent. Waiting for re-enumeration...";
                // We don't return an ID here. We return "" and let the loop run again.
                // The next scan (in 1-2 seconds) will catch it in Path A.
                std::this_thread::sleep_for(std::chrono::milliseconds(2000));
                
                // Optional: Break here to save CPU, since the device is rebooting anyway
                break; 
            }
        }
    }

    libusb_free_device_list(devs, 1);
    libusb_exit(ctx);
    return foundId;
}