#include "mainwindow.h"
#include "ui_mainwindow.h"
#include <iostream>
#include <string>
#include <QList>
#include <QScreen>
#include <QWindow>
#include <QCheckBox>
#include <QtConcurrent/QtConcurrent>
#include "linux-adk.h" 

using namespace std;

// Initialize static member
bool MainWindow::isDebugMode = false;

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
    , ui(new Ui::MainWindow)
{
    ui->setupUi(this);

    // 1. Initialize Helpers first
    displayScreenTranslator = new DisplayScreenTranslator();
    pressureTranslator = new PressureTranslator();
    
    // 2. Initialize VirtualStylus with dependencies (Fixes constructor error)
    virtualStylus = new VirtualStylus(displayScreenTranslator, pressureTranslator);
    
    usbDevices = new QMap<string, string>();

    // 3. Setup UI Defaults
    // Fix: Access swapAxis via the instance, not static
    ui->cbSwapAxis->setChecked(virtualStylus->swapAxis);

    // C++20 Fix: Explicit capture of 'this'
    connect(ui->cbSwapAxis, &QCheckBox::toggled, this, [=, this](bool checked){
        virtualStylus->swapAxis = checked;
        if(isDebugMode) cout << "Swap Axis toggled: " << checked << endl;
    });

    // 4. Populate Screens and Calculate Geometry
    screenGeometries.clear();
    QList<QScreen*> screens = QGuiApplication::screens();
    
    QRect totalRect;
    int index = 0;
    
    for(QScreen* screen : screens){
        ui->displayStyleComboBox->addItem(screen->name());
        screenGeometries.append(screen->geometry());
        totalRect = totalRect.united(screen->geometry());
        index++;
    }

    // Pass the total desktop size to the stylus for coordinate normalization
    virtualStylus->setTotalDesktopGeometry(totalRect);

    // Default to first screen if available
    if(!screens.isEmpty()){
        virtualStylus->setTargetScreen(screenGeometries[0]);
    }
    // --- ADD THIS LINE ---
    virtualStylus->initializeStylus(); 
    // ---------------------
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

    // Instantiate the new C++20 UsbConnection class
    InkBridge::UsbConnection connection;
    
    // This blocks until the USB device is disconnected or an error occurs
    int result = connection.startCapture(selectedDevice, virtualStylus);
    
    if (result != 0 && isDebugMode) {
        cout << "Capture finished with error code: " << result << endl;
    }
}

// --- Renamed to match mainwindow.h ---
void MainWindow::on_refreshUsbDevices_clicked()
{
    // Clear list
    ui->usbDevicesListWidget->clear(); // Fix: Matches header name
    usbDevices->clear();

    // Enumerate USB devices using LibUSB
    libusb_context *ctx = nullptr;
    libusb_device **devs = nullptr;
    int r;
    ssize_t cnt;

    r = libusb_init(&ctx);
    if (r < 0) return;

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

        // Simple label
        string value = "USB Device " + key;

        usbDevices->insert(key, value);
        ui->usbDevicesListWidget->addItem(QString::fromStdString(value));
    }

    libusb_free_device_list(devs, 1);
    libusb_exit(ctx);
}

// --- Renamed to match mainwindow.h ---
void MainWindow::on_usbDevicesListWidget_itemClicked(QListWidgetItem *item)
{
    QString text = item->text();
    // Extract ID from string "USB Device XXXX:YYYY"
    // Assuming format "USB Device 1234:5678" -> substr(11) gets "1234:5678"
    if (text.length() > 11) {
        string id = text.toStdString().substr(11); 
        selectedDevice = id;
        if(isDebugMode) cout << "Selected device: " << selectedDevice << endl;
    }
}

// --- Renamed to match mainwindow.h ---
void MainWindow::on_connectUsbButton_clicked()
{
    if(selectedDevice.empty()){
        if(isDebugMode) cout << "Please select a device first." << endl;
        return;
    }

    ui->connectUsbButton->setEnabled(false);
    ui->refreshUsbDevices->setEnabled(false);
    
    // Run capture in a background thread
    QtConcurrent::run([=, this](){
        this->captureStylusInput();
        
        // When capture ends, re-enable buttons on UI thread
        QMetaObject::invokeMethod(this, [=, this](){
             ui->connectUsbButton->setEnabled(true);
             ui->refreshUsbDevices->setEnabled(true);
        });
    });
}

void MainWindow::on_displayStyleComboBox_currentIndexChanged(int index)
{
    // Fix: Use the vector we populated in the constructor
    if(index >= 0 && index < screenGeometries.size()){
        virtualStylus->setTargetScreen(screenGeometries[index]);
    }
}

// Empty implementations for unused slots (to satisfy linker)
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