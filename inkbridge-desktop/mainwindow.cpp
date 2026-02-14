#include <QSettings>
#include <QIntValidator>
#include <QStyleFactory>
#include <QDialog>
#include <QMessageBox>
#include <QMetaObject>
#include <QtConcurrent/QtConcurrent>
#include <QDesktopServices>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <libusb-1.0/libusb.h>
#include <unistd.h>

#include "mainwindow.h"
#include "ui_mainwindow.h"
#include "ui_udevdialog.h"
#include <linux/uinput.h>
#include "linux-adk.h"
#include "virtualstylus.h"
#include "protocol.h"
#include <QScreen>
#include <QGuiApplication>
#include <QVBoxLayout>

using namespace QtConcurrent;
using namespace std;
namespace fs = std::filesystem;
bool MainWindow::isDebugMode{ false };

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
    , ui(new Ui::MainWindow)
    , selectedDeviceIdentifier("")
    , selectedDevice("")
    , usbDevices(new QMap<string, string>())
    , displayScreenTranslator(new DisplayScreenTranslator())
    , pressureTranslator(new PressureTranslator())
{
    // --- FIX 1: Initialize the Stylus Driver HERE ---
    virtualStylus = new VirtualStylus(displayScreenTranslator, pressureTranslator);
    // ------------------------------------------------

    settings = new QSettings(setting_org, setting_app);
    filePermissionValidator = new FilePermissionValidator();
    dialog = new QDialog();
    ui->setupUi(this);

    // Connect the checkbox to the lambda function
    connect(ui->cbSwapAxis, &QCheckBox::toggled, this, [=](bool checked){
        if(this->virtualStylus != nullptr){
            this->virtualStylus->swapAxis = checked;
            qDebug() << "Swap Axis set to:" << checked;
        }
    });

    // --- MONITOR SELECTOR SETUP (FIXED POSITION) ---
    // 1. Shrink the USB List slightly to make room
    ui->usbDevicesListWidget->setGeometry(30, 60, 331, 160);

    // 2. Create the Dropdown and set exact coordinates
    monitorSelector = new QComboBox(this);
    monitorSelector->setGeometry(30, 225, 331, 25); 
    
    // 3. Get List of Screens
    const auto screens = QGuiApplication::screens();
    
    // 4. Populate the Dropdown
    QString savedMonitor = getSetting("/target_monitor").toString();
    int indexToSelect = 0; // Default to first monitor

    for (int i = 0; i < screens.size(); ++i) {
        QScreen *screen = screens[i];
        QString name = screen->name(); 
        QRect geometry = screen->geometry();
        
        QString label = QString("%1 (%2x%3)")
                        .arg(name)
                        .arg(geometry.width())
                        .arg(geometry.height());
                        
        monitorSelector->addItem(label);
        screenGeometries.append(geometry);

        // CHECK: Is this the saved monitor?
        if (name == savedMonitor) {
            indexToSelect = i;
        }
    }

    // 5. Connect the signal (Moved AFTER population to avoid triggering during setup)
    connect(monitorSelector, SIGNAL(currentIndexChanged(int)), 
            this, SLOT(on_monitorChanged(int)));
            
    // 6. Restore the selection
    monitorSelector->setCurrentIndex(indexToSelect);
    // Manually trigger the update so the driver gets the data immediately
    on_monitorChanged(indexToSelect);

    // --- END SETUP ---

    ui->deviceXSize->setValidator(new QIntValidator(1, max_device_size, this));
    ui->deviceYSize->setValidator(new QIntValidator(1, max_device_size, this));
    ui->wifiPortInput->setValidator(new QIntValidator(1, 65535, this));
    ui->wifiPortInput->setText(getSetting(wifi_port_setting_key, QVariant::fromValue(4545)).toString());
    initDisplayStyles();
    libUsbContext = libusb_init(NULL);
    updateUsbConnectButton();
    populateUsbDevicesList();
}

void MainWindow::captureStylusInput(){
    // --- FIX 2: Do NOT re-create the object here, use the existing one ---
    // virtualStylus = new VirtualStylus(...); // DELETED
    virtualStylus->initializeStylus();
    capture(selectedDevice, virtualStylus);
}

void MainWindow::captureWifiInput(int port){
    // Note: This creates a separate instance for WiFi. 
    // If you want WiFi to also respect monitor settings, you'll need to refactor this later.
    VirtualStylus* wifiStylus = new VirtualStylus(displayScreenTranslator, pressureTranslator);
    wifiStylus->initializeStylus();
    int serverFd = socket(AF_INET, SOCK_STREAM, 0);
    if(serverFd < 0){
        wifiRunning.store(false);
        QMetaObject::invokeMethod(ui->connectionStatusLabel, "setText", Qt::QueuedConnection,
                                  Q_ARG(QString, QString::fromUtf8("WiFi Error")));
        QMetaObject::invokeMethod(this, [this]() { updateUsbConnectButton(); }, Qt::QueuedConnection);
        return;
    }
    int reuse = 1;
    setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = INADDR_ANY;
    address.sin_port = htons(static_cast<uint16_t>(port));
    if(bind(serverFd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) < 0){
        ::close(serverFd);
        wifiRunning.store(false);
        QMetaObject::invokeMethod(ui->connectionStatusLabel, "setText", Qt::QueuedConnection,
                                  Q_ARG(QString, QString::fromUtf8("WiFi Error")));
        QMetaObject::invokeMethod(this, [this]() { updateUsbConnectButton(); }, Qt::QueuedConnection);
        return;
    }
    if(listen(serverFd, 1) < 0){
        ::close(serverFd);
        wifiRunning.store(false);
        QMetaObject::invokeMethod(ui->connectionStatusLabel, "setText", Qt::QueuedConnection,
                                  Q_ARG(QString, QString::fromUtf8("WiFi Error")));
        QMetaObject::invokeMethod(this, [this]() { updateUsbConnectButton(); }, Qt::QueuedConnection);
        return;
    }

    while(wifiRunning.load()){
        int clientFd = accept(serverFd, nullptr, nullptr);
        if(clientFd < 0){
            continue;
        }
        QMetaObject::invokeMethod(ui->connectionStatusLabel, "setText", Qt::QueuedConnection,
                                  Q_ARG(QString, QString::fromUtf8("WiFi Connected!")));
        
        std::vector<uint8_t> pending;
        AccessoryEventData accessoryEventData{};
        uint8_t buffer[1024];
        while(wifiRunning.load()){
            ssize_t bytesRead = recv(clientFd, buffer, sizeof(buffer), 0);
            if(bytesRead <= 0){
                break;
            }
            pending.insert(pending.end(), buffer, buffer + bytesRead);
            
            while(pending.size() >= sizeof(PenPacket)){
                PenPacket packet;
                memcpy(&packet, pending.data(), sizeof(PenPacket));
                
                if (MainWindow::isDebugMode) {
                    qDebug() << "Binary Packet (WiFi):" 
                             << "toolType:" << packet.toolType 
                             << "action:" << packet.action 
                             << "x:" << packet.x 
                             << "y:" << packet.y 
                             << "pressure:" << packet.pressure;
                }

                accessoryEventData.toolType = packet.toolType;
                accessoryEventData.action = packet.action;
                accessoryEventData.x = packet.x;
                accessoryEventData.y = packet.y;
                accessoryEventData.pressure = (float)packet.pressure / 1000.0f;
                
                wifiStylus->handleAccessoryEventData(&accessoryEventData);
                pending.erase(pending.begin(), pending.begin() + sizeof(PenPacket));
            }
        }
        ::close(clientFd);
        QMetaObject::invokeMethod(ui->connectionStatusLabel, "setText", Qt::QueuedConnection,
                                  Q_ARG(QString, QString::fromUtf8("WiFi Listening...")));
    }
    ::close(serverFd);
    wifiRunning.store(false);
    QMetaObject::invokeMethod(this, [this]() { updateUsbConnectButton(); }, Qt::QueuedConnection);
    delete wifiStylus;
}

void MainWindow::populateUsbDevicesList(){
    ui->usbDevicesListWidget->clear();
    fetchUsbDevices();    
    foreach(const string key, usbDevices->keys()){
        ui->usbDevicesListWidget->addItem(QString::fromStdString(key));
    }
}


void MainWindow::on_usbDevicesListWidget_itemClicked(QListWidgetItem *item){
    selectedDeviceIdentifier = item->text().toStdString();
    selectedDevice = usbDevices->value(selectedDeviceIdentifier);
    loadDeviceConfig();
    updateUsbConnectButton();
}

bool MainWindow::canConnectUsb(){
    if(selectedDevice != "" &&
        ui->deviceXSize->hasAcceptableInput() &&
        ui->deviceYSize->hasAcceptableInput() &&
        displayScreenTranslator->size_x != -1 &&
        displayScreenTranslator->size_y != -1){
        return true;
    }
    else{
        return false;
    }
}

bool MainWindow::canStartWifi(){
    return ui->deviceXSize->hasAcceptableInput() &&
           ui->deviceYSize->hasAcceptableInput() &&
           ui->wifiPortInput->hasAcceptableInput();
}

void MainWindow::updateUsbConnectButton(){
    ui->connectUsbButton->setEnabled(canConnectUsb());
    ui->startWifiButton->setEnabled(canStartWifi() && !wifiRunning.load());
}


void MainWindow::on_connectUsbButton_clicked()
{
    // --- 1. SAFETY CHECK: Null Pointer Protection ---
    if (!virtualStylus) {
        qDebug() << "CRITICAL ERROR: virtualStylus is not initialized!";
        return; 
    }

    // --- 2. SETUP RESOLUTION ---
    bool okX, okY;
    int w = ui->deviceXSize->text().toInt(&okX);
    int h = ui->deviceYSize->text().toInt(&okY);

    if (!okX || w <= 0) w = 2560; 
    if (!okY || h <= 0) h = 1600; 

    virtualStylus->setInputResolution(w, h);
    
    // --- 3. SETUP MONITOR MAPPING ---
    if (monitorSelector && !screenGeometries.isEmpty()) {
        int index = monitorSelector->currentIndex();
        if (index >= 0 && index < screenGeometries.size()) {
            virtualStylus->setTargetScreen(screenGeometries[index]);
            
            // NEW: Pass the Total Desktop Geometry (Union of all screens)
            // In Qt, the primary screen's 'virtualGeometry' usually returns the bounding box of the whole setup.
            virtualStylus->setTotalDesktopGeometry(QGuiApplication::primaryScreen()->virtualGeometry());
        }
    }

    // --- 4. START CONNECTION ---
    displayUDevPermissionFixIfNeeded();
    
    // Start the capture loop in a background thread
    QFuture<void> ignored = QtConcurrent::run([this] { return captureStylusInput(); });

    // Update UI
    ui->connectionStatusLabel->setText(QString::fromUtf8("Connected!"));
    ui->connectUsbButton->setEnabled(false);
    ui->refreshUsbDevices->setEnabled(false);
    ui->usbDevicesListWidget->setEnabled(false);
}

void MainWindow::on_startWifiButton_clicked(){
    displayUDevPermissionFixIfNeeded();
    int port = ui->wifiPortInput->text().toInt();
    wifiRunning.store(true);
    QFuture<void> ignored = QtConcurrent::run([this, port] { return captureWifiInput(port); });
    ui->connectionStatusLabel->setText(QString::fromUtf8("WiFi Listening..."));
    updateUsbConnectButton();
}

void MainWindow::fetchUsbDevices(){
    usbDevices->clear();
    libusb_device ** devicesList = NULL;
    ssize_t nbDevices = libusb_get_device_list(NULL, &devicesList);
    int err = 0;
    for(ssize_t i = 0; i < nbDevices; i++){
        libusb_device *dev = devicesList[i];
        struct libusb_device_descriptor desc;
        err = libusb_get_device_descriptor(dev, &desc);
        libusb_device_handle *handle = NULL;
        err = libusb_open(dev, &handle);
        if (err) {
            if(MainWindow::isDebugMode){
                printf("Unable to open device...\n");
            }
            continue;
        }
        unsigned char buf[100];
        int descLength = -1;
        descLength = libusb_get_string_descriptor_ascii(handle, desc.iManufacturer, buf, sizeof(buf));
        if(descLength < 0){
            continue;
        }
        string manufacturer = reinterpret_cast<char*>(buf);

        descLength = libusb_get_string_descriptor_ascii(handle, desc.iProduct, buf, sizeof(buf));
        if(descLength < 0){
            continue;
        }
        string product = reinterpret_cast<char*>(buf);

        descLength = libusb_get_string_descriptor_ascii(handle, desc.iSerialNumber, buf, sizeof(buf));
        if(descLength < 0){
            continue;
        }
        string serialNumber = reinterpret_cast<char*>(buf);

        std::ostringstream ss;
        ss<< std::hex << desc.idVendor << ":" << std::hex << desc.idProduct;
        string device = ss.str();

        usbDevices->insert(manufacturer + "-" + product + " (" + serialNumber + ")", device);
    }
}

void MainWindow::initDisplayStyles(){
    ui->displayStyleComboBox->addItem("  Stretched", static_cast<int>(DisplayStyle::stretched));
    ui->displayStyleComboBox->addItem("  Fixed", static_cast<int>(DisplayStyle::fixed));
}


void MainWindow::on_deviceXSize_editingFinished()
{
    displayScreenTranslator->size_x = ui->deviceXSize->text().toInt();
    setSetting(x_device_setting_key, displayScreenTranslator->size_x);
    updateUsbConnectButton();
}


void MainWindow::on_deviceYSize_editingFinished()
{
    displayScreenTranslator->size_y = ui->deviceYSize->text().toInt();
    setSetting(y_device_setting_key, displayScreenTranslator->size_y);
    updateUsbConnectButton();
}

void MainWindow::on_wifiPortInput_editingFinished()
{
    setSetting(wifi_port_setting_key, ui->wifiPortInput->text().toInt());
    updateUsbConnectButton();
}

void MainWindow::manageInputBoxStyle(QLineEdit * inputBox){
    if(inputBox->hasAcceptableInput()){
        inputBox->setStyleSheet("QLineEdit{border: 1px solid white}");
    }
    else{
        inputBox->setStyleSheet("QLineEdit{border: 1px solid red}");
    }
}


void MainWindow::on_displayStyleComboBox_currentIndexChanged(int index)
{
    int displayStyleInt = ui->displayStyleComboBox->currentData().toInt();
    displayScreenTranslator->displayStyle = static_cast<DisplayStyle>(displayStyleInt);
    setSetting(display_style_setting_key, displayStyleInt);
}

void MainWindow::on_pressureSensitivitySlider_valueChanged(int value)
{
    pressureTranslator->sensitivity = value;
    setSetting(pressure_sensitivity_setting_key, value);
}


void MainWindow::on_minimumPressureSlider_valueChanged(int value)
{
    pressureTranslator->minPressure = value;
    setSetting(min_pressure_setting_key, value);
}

void MainWindow::loadDeviceConfig(){
    displayScreenTranslator->size_x = getSetting(x_device_setting_key).toInt();
    displayScreenTranslator->size_y = getSetting(y_device_setting_key).toInt();
    int displayStyleInt = getSetting(display_style_setting_key).toInt();
    int index = ui->displayStyleComboBox->findData(displayStyleInt);
    if ( index != -1 ) {
        displayScreenTranslator->displayStyle = static_cast<DisplayStyle>(displayStyleInt);
        ui->displayStyleComboBox->setCurrentIndex(index);
    }
    else{
        displayScreenTranslator->displayStyle = DisplayStyle::stretched;
        ui->displayStyleComboBox->setCurrentIndex(ui->displayStyleComboBox->findData(static_cast<int>(DisplayStyle::stretched)));
    }

    ui->displayStyleComboBox->activated(0);
    pressureTranslator->minPressure = getSetting(min_pressure_setting_key, QVariant::fromValue(10)).toInt();
    ui->minimumPressureSlider->setValue(pressureTranslator->minPressure);
    pressureTranslator->sensitivity = getSetting(pressure_sensitivity_setting_key, QVariant::fromValue(50)).toInt();
    ui->pressureSensitivitySlider->setValue(pressureTranslator->sensitivity);
    ui->deviceXSize->setText(QString::number(displayScreenTranslator->size_x));
    ui->deviceYSize->setText(QString::number(displayScreenTranslator->size_y));
    ui->wifiPortInput->setText(getSetting(wifi_port_setting_key, QVariant::fromValue(4545)).toString());
    on_deviceXSize_selectionChanged();
    on_deviceYSize_selectionChanged();
}

QVariant MainWindow::getSetting(string settingKey){
    return settings->value(QString::fromStdString(selectedDeviceIdentifier + settingKey));
}


QVariant MainWindow::getSetting(string settingKey, QVariant defaultValue){
    QVariant value = getSetting(settingKey);
    return value.isNull() ? defaultValue : value;
}

void MainWindow::setSetting(string settingKey, QVariant value){
    return settings->setValue(QString::fromStdString(selectedDeviceIdentifier + settingKey), value);
}

void MainWindow::on_refreshUsbDevices_clicked()
{
    populateUsbDevicesList();    
}

void MainWindow::displayUDevPermissionFixIfNeeded(){
    bool canWriteToUInput = filePermissionValidator->canWriteToFile("/dev/uinput");
    bool canWriteToUsbDevice = canWriteToAnyUsbDevice();
    if(!canWriteToUInput || !canWriteToUsbDevice){
        displayFixForUDevPermissions();
    }
}

bool MainWindow::canWriteToAnyUsbDevice(){
    if(!usbDevices->empty()){
        return filePermissionValidator->anyFileWriteableRecursive("/dev/bus/usb/");
    }
    else{
        return true;
    }

}


void MainWindow::displayFixForUDevPermissions(){

    QWidget widget;
    Ui::udevdialog udevDialog;
    udevDialog.setupUi(dialog);
    dialog->exec();
}


void MainWindow::on_deviceXSize_selectionChanged()
{
    manageInputBoxStyle(ui->deviceXSize);
    updateUsbConnectButton();
}


void MainWindow::on_deviceYSize_selectionChanged()
{
        manageInputBoxStyle(ui->deviceYSize);
        updateUsbConnectButton();
}

void MainWindow::on_connectUsbButton_2_clicked()
{
    QString link = "https://github.com/androidvirtualpen/virtualpen/releases/download/0.1/virtual-pen.apk";
    QDesktopServices::openUrl(QUrl(link));
}

void MainWindow::on_monitorChanged(int index)
{
    if (index < 0 || index >= screenGeometries.size()) return;

    QRect selectedGeo = screenGeometries[index];
    
    // Pass the selected monitor geometry to the driver
    virtualStylus->setTargetScreen(selectedGeo);
    
    // Also pass the Tablet Resolution (from the text boxes)
    bool okX, okY;
    int w = ui->deviceXSize->text().toInt(&okX);
    int h = ui->deviceYSize->text().toInt(&okY);
    
    if (okX && okY) {
        virtualStylus->setInputResolution(w, h);
    }

    // 3. NEW: Save the Monitor Name to settings
    // We save the Name (e.g. "DisplayPort-6") not the index, so it works if you swap ports.
    QString selectedName = monitorSelector->itemText(index);
    // Strip the resolution part " (2560x1600)" to store just the ID
    selectedName = selectedName.split(" (").first(); 
    
    setSetting("/target_monitor", selectedName);
}

MainWindow::~MainWindow()
{
    delete ui;
    delete usbDevices;
    delete displayScreenTranslator;
    delete pressureTranslator;
    delete settings;
    delete filePermissionValidator;
    // --- FIX 3: Cleanup ---
    delete virtualStylus;
}