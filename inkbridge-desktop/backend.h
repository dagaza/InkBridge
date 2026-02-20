#ifndef BACKEND_H
#define BACKEND_H

#include <QObject>
#include <QStringList>
#include <QRect>
#include <QVector>
#include <QScreen>
#include <QGuiApplication>
#include <QDebug>
#include <QtConcurrent/QtConcurrent>
#include <QVariantList> 
#include <atomic> // REQUIRED
#include <thread> // REQUIRED
#include <chrono> // REQUIRED
#include <libusb-1.0/libusb.h>
#include "wifidirectserver.h"
#include "virtualstylus.h"
#include "accessory.h"
#include "displayscreentranslator.h"
#include "pressuretranslator.h"
#include "bluetoothserver.h"

class Backend : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QStringList screenList READ screenList NOTIFY screenListChanged)
    Q_PROPERTY(QVariantList screenGeometries READ screenGeometries NOTIFY screenListChanged)
    Q_PROPERTY(QString connectionStatus READ connectionStatus NOTIFY connectionStatusChanged)
    Q_PROPERTY(bool isConnected READ isConnected NOTIFY isConnectedChanged)
    Q_PROPERTY(QStringList usbDevices READ usbDevices NOTIFY usbDevicesChanged)
    Q_PROPERTY(bool isWifiRunning READ isWifiDirectRunning NOTIFY wifiDirectStatusChanged)
    Q_PROPERTY(int pressureSensitivity READ pressureSensitivity NOTIFY settingsChanged)
    Q_PROPERTY(int minPressure READ minPressure NOTIFY settingsChanged)
    Q_PROPERTY(bool swapAxis READ swapAxis NOTIFY settingsChanged)
    Q_PROPERTY(bool isBluetoothRunning READ isBluetoothRunning NOTIFY bluetoothStatusChanged)


public:
    static bool isDebugMode;
    explicit Backend(QObject *parent = nullptr);
    ~Backend();

    QStringList screenList() const;
    QVariantList screenGeometries() const;
    QString connectionStatus() const;
    bool isConnected() const;
    QStringList usbDevices() const;
    bool isWifiDirectRunning() const;
    int pressureSensitivity() const;
    int minPressure() const;
    bool swapAxis() const;

    // --- NEW: Auto-Connect Public Methods ---
    Q_INVOKABLE void startAutoConnect();
    Q_INVOKABLE void stopAutoConnect();
    // NEW: The "Software Eject" button
    Q_INVOKABLE void forceUsbReset();

    bool isBluetoothRunning() const;


public slots:
    void refreshScreens();
    void refreshUsbDevices();
    void selectScreen(int index);
    void connectDevice(int deviceIndex);
    void disconnectDevice();
    void setPressureSensitivity(int value);
    void setMinPressure(int value);
    void setSwapAxis(bool swap);
    void toggleWifiDirect();
    void toggleDebug(bool enable);
    void resetDefaults();
    void toggleBluetooth();

signals:
    void screenListChanged();
    void connectionStatusChanged();
    void isConnectedChanged();
    void usbDevicesChanged();
    void wifiDirectStatusChanged();
    void settingsChanged();
    void bluetoothStatusChanged();

private:
    VirtualStylus *m_stylus;
    DisplayScreenTranslator *m_displayTranslator;
    PressureTranslator *m_pressureTranslator;
    WifiDirectServer *m_wifiDirectServer;
    BluetoothServer *m_bluetoothServer;
        
    QVector<QRect> m_screenRects;
    QVariantList m_screenGeometriesVariant;
    QStringList m_screenNames;
    QStringList m_usbDeviceIds;
    QStringList m_usbDeviceNames;
    
    QString m_status;
    bool m_connected;
    bool m_wifiDirectRunning;
    bool m_bluetoothRunning;
    bool m_screenSelected;
    bool trySwitchToAccessoryMode(libusb_device *dev, libusb_device_handle *handle);
    
    int m_pressureSensitivity;
    int m_minPressure;
    bool m_swapAxis;

    void updateStatus(QString msg, bool connected);
    void handleWifiDirectData(QByteArray data);
    void handleBluetoothData(QByteArray data);

    // Carry-over buffers for partial packets that arrive split across two
    // consecutive readyRead signals. Each transport has its own buffer so
    // they never interfere with each other.
    QByteArray m_wifiLeftover;
    QByteArray m_btLeftover;

    // --- NEW: Auto-Connect Private Members ---
    std::atomic<bool> m_autoScanRunning;
    std::thread m_autoScanThread;
    
    // These were missing from your header, causing the error:
    void autoConnectLoop();
    QString scanForInkBridgeDevice();
};

#endif // BACKEND_H