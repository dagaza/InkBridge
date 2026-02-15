#include "backend.h"
#include "linux-adk.h"
#include <libusb-1.0/libusb.h>

bool Backend::isDebugMode = false;

Backend::Backend(QObject *parent) 
    : QObject(parent)
    , m_status("Ready")     
    , m_connected(false)
    , m_wifiRunning(false)  // <--- FIX: Initialize m_wifiRunning to false
    , m_pressureSensitivity(50)
    , m_minPressure(0)
    , m_swapAxis(false)
{
    m_displayTranslator = new DisplayScreenTranslator();
    m_pressureTranslator = new PressureTranslator();
    m_stylus = new VirtualStylus(m_displayTranslator, m_pressureTranslator);
    
    m_stylus->initializeStylus();
    refreshScreens();
    refreshUsbDevices();
}

Backend::~Backend() {
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
bool Backend::isWifiRunning() const { return m_wifiRunning; }
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
        // Logic to update translator would go here
        emit settingsChanged(); // <--- FIX: This updates the text in QML
    }
}

void Backend::setMinPressure(int value) {
    if (m_minPressure != value) {
        m_minPressure = value;
        emit settingsChanged(); // <--- FIX: This updates the text in QML
    }
}

void Backend::setSwapAxis(bool swap) {
    m_swapAxis = swap;
    m_stylus->swapAxis = swap;
    emit settingsChanged();
}

void Backend::toggleWifi() {
    m_wifiRunning = !m_wifiRunning;
    
    // Placeholder logic for Wi-Fi Server
    if (m_wifiRunning) {
        qDebug() << "Wi-Fi Server Started";
        // Start your server logic here
    } else {
        qDebug() << "Wi-Fi Server Stopped";
        // Stop server logic here
    }
    emit wifiStatusChanged();
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