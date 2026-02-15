#include "mainwindow.h"
#include "ui_mainwindow.h"
#include <iostream>
#include <string>
#include <QList>
#include <QScreen>
#include <QWindow>
#include <QCheckBox>
#include <QtConcurrent/QtConcurrent>
#include <libusb-1.0/libusb.h> // Required for device names
#include "linux-adk.h" 
#include "protocol.h" // Ensure we have access to packet defs if needed

using namespace std;

// Initialize static member
bool MainWindow::isDebugMode = false;

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
    , ui(new Ui::MainWindow)
{
    ui->setupUi(this);

    // 1. Initialize Helpers
    displayScreenTranslator = new DisplayScreenTranslator();
    pressureTranslator = new PressureTranslator();
    
    // 2. Initialize VirtualStylus
    virtualStylus = new VirtualStylus(displayScreenTranslator, pressureTranslator);
    usbDevices = new QMap<string, string>();

    // 3. Setup UI - Swap Axis
    ui->cbSwapAxis->setChecked(virtualStylus->swapAxis);
    connect(ui->cbSwapAxis, &QCheckBox::toggled, this, [=, this](bool checked){
        virtualStylus->swapAxis = checked;
        if(isDebugMode) cout << "Swap Axis toggled: " << checked << endl;
    });

    // 4. Populate Screens and Calculate Geometry
    // We use displayStyleComboBox as the "Screen Selector"
    ui->displayStyleComboBox->clear();
    screenGeometries.clear();
    
    QList<QScreen*> screens = QGuiApplication::screens();
    QRect totalRect;
    
    if (screens.isEmpty()) {
        cout << "WARNING: No screens detected via QGuiApplication!" << endl;
    }

    for(QScreen* screen : screens){
        // Add screen name to dropdown (e.g., "DP-1", "HDMI-0")
        ui->displayStyleComboBox->addItem(screen->name() + " (" + QString::number(screen->geometry().width()) + "x" + QString::number(screen->geometry().height()) + ")");
        screenGeometries.append(screen->geometry());
        totalRect = totalRect.united(screen->geometry());
    }

    // Pass the total desktop size to the stylus for global coordinate normalization
    virtualStylus->setTotalDesktopGeometry(totalRect);

    // Default to first screen if available
    if(!screens.isEmpty()){
        virtualStylus->setTargetScreen(screenGeometries[0]);
    }

    // 5. Connect Calibration Button
    // Assuming you added a button named 'calibrationButton' to your .ui file
    connect(ui->calibrationButton, &QPushButton::clicked, this, [=, this](){
        // Toggle debug mode effectively acts as calibration mode
        isDebugMode = !isDebugMode;
        cout << "Calibration/Debug Mode: " << (isDebugMode ? "ON" : "OFF") << endl;
        
        // Visual feedback on the button
        if(isDebugMode) ui->calibrationButton->setText("Stop Calibrating");
        else ui->calibrationButton->setText("Calibrate Tilt");
    });

    // Initialize the uinput device
    virtualStylus->initializeStylus(); 
}

MainWindow::~MainWindow()
{
    delete ui;
    delete virtualStylus;
    delete usbDevices;
    delete displayScreenTranslator;
    delete pressureTranslator;
}

void MainWindow::captureStylusInput()
{
    if(selectedDevice.empty()) {
        if(isDebugMode) cout << "No device selected." << endl;
        return;
    }

    // Instantiate UsbConnection
    InkBridge::UsbConnection connection;
    
    cout << "Starting capture on device: " << selectedDevice << endl;
    
    // This blocks until disconnected
    int result = connection.startCapture(selectedDevice, virtualStylus);
    
    if (result != 0 && isDebugMode) {
        cout << "Capture finished with error code: " << result << endl;
    }
}

// --- ENHANCED USB DISCOVERY ---
void MainWindow::on_refreshUsbDevices_clicked()
{
    ui->usbDevicesListWidget->clear(); 
    usbDevices->clear();

    libusb_context *ctx = nullptr;
    libusb_device **devs = nullptr;
    int r;
    ssize_t cnt;

    r = libusb_init(&ctx);
    if (r < 0) {
        cerr << "Failed to init libusb" << endl;
        return;
    }

    cnt = libusb_get_device_list(ctx, &devs);
    if (cnt < 0) {
        libusb_exit(ctx);
        return;
    }

    for (ssize_t i = 0; i < cnt; i++) {
        libusb_device *dev = devs[i];
        struct libusb_device_descriptor desc;
        
        if (libusb_get_device_descriptor(dev, &desc) < 0) continue;

        // Create ID string "VID:PID"
        char idStr[10];
        sprintf(idStr, "%04x:%04x", desc.idVendor, desc.idProduct);
        string key(idStr);

        // --- NEW: Get Manufacturer/Product Name ---
        unsigned char manufacturer[256] = "Unknown";
        unsigned char product[256] = "Device";
        
        libusb_device_handle *handle = nullptr;
        // Try to open to read strings (might fail on root hubs, that's fine)
        if (libusb_open(dev, &handle) == 0) {
            if (desc.iManufacturer) {
                libusb_get_string_descriptor_ascii(handle, desc.iManufacturer, manufacturer, sizeof(manufacturer));
            }
            if (desc.iProduct) {
                libusb_get_string_descriptor_ascii(handle, desc.iProduct, product, sizeof(product));
            }
            libusb_close(handle);
        }

        // Filter: Optional - Only show likely Android devices or specific vendors?
        // For now, show everything so we can debug.
        string value = string((char*)manufacturer) + " " + string((char*)product) + " [" + key + "]";

        usbDevices->insert(key, value);
        ui->usbDevicesListWidget->addItem(QString::fromStdString(value));
    }

    libusb_free_device_list(devs, 1);
    libusb_exit(ctx);
}

void MainWindow::on_usbDevicesListWidget_itemClicked(QListWidgetItem *item)
{
    QString text = item->text();
    // Extract ID from string "[XXXX:YYYY]" at the end
    int bracketPos = text.lastIndexOf('[');
    if (bracketPos != -1 && text.endsWith(']')) {
        string id = text.mid(bracketPos + 1, text.length() - bracketPos - 2).toStdString();
        selectedDevice = id;
        if(isDebugMode) cout << "Selected device ID: " << selectedDevice << endl;
    } else {
        // Fallback for old format
        if (text.length() > 11) {
             selectedDevice = text.toStdString().substr(11);
        }
    }
}

void MainWindow::on_connectUsbButton_clicked()
{
    if(selectedDevice.empty()){
        if(isDebugMode) cout << "Please select a device first." << endl;
        return;
    }

    ui->connectUsbButton->setEnabled(false);
    ui->refreshUsbDevices->setEnabled(false);
    
    QtConcurrent::run([=, this](){
        this->captureStylusInput();
        
        QMetaObject::invokeMethod(this, [=, this](){
             ui->connectUsbButton->setEnabled(true);
             ui->refreshUsbDevices->setEnabled(true);
        });
    });
}

void MainWindow::on_displayStyleComboBox_currentIndexChanged(int index)
{
    if(index >= 0 && index < screenGeometries.size()){
        QRect target = screenGeometries[index];
        virtualStylus->setTargetScreen(target);
        if(isDebugMode) {
             cout << "Target Screen Changed to Index " << index 
                  << " Geometry: " << target.width() << "x" << target.height() 
                  << " @ " << target.x() << "," << target.y() << endl;
        }
    }
}

// Unused slots
void MainWindow::on_deviceXSize_editingFinished() {}
void MainWindow::on_deviceYSize_editingFinished() {}
void MainWindow::on_pressureSensitivitySlider_valueChanged(int value) { Q_UNUSED(value); }
void MainWindow::on_minimumPressureSlider_valueChanged(int value) { Q_UNUSED(value); }
void MainWindow::on_deviceXSize_selectionChanged() {}
void MainWindow::on_deviceYSize_selectionChanged() {}
void MainWindow::on_connectUsbButton_2_clicked() {}
void MainWindow::on_startWifiButton_clicked() {}
void MainWindow::on_wifiPortInput_editingFinished() {}
void MainWindow::on_monitorChanged(int index) { Q_UNUSED(index); }