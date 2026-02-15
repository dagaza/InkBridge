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
#include <QVariantList> // <--- Added
#include "virtualstylus.h"
#include "accessory.h"
#include "displayscreentranslator.h"
#include "pressuretranslator.h"

class Backend : public QObject
{
    Q_OBJECT
    Q_PROPERTY(QStringList screenList READ screenList NOTIFY screenListChanged)
    // --- NEW: Expose raw geometry to QML for drawing ---
    Q_PROPERTY(QVariantList screenGeometries READ screenGeometries NOTIFY screenListChanged)
    
    Q_PROPERTY(QString connectionStatus READ connectionStatus NOTIFY connectionStatusChanged)
    Q_PROPERTY(bool isConnected READ isConnected NOTIFY isConnectedChanged)
    Q_PROPERTY(QStringList usbDevices READ usbDevices NOTIFY usbDevicesChanged)

    // --- NEW: Independent Wi-Fi State ---
    Q_PROPERTY(bool isWifiRunning READ isWifiRunning NOTIFY wifiStatusChanged)
    
    // --- NEW: Expose Settings for Binding ---
    Q_PROPERTY(int pressureSensitivity READ pressureSensitivity NOTIFY settingsChanged)
    Q_PROPERTY(int minPressure READ minPressure NOTIFY settingsChanged)
    Q_PROPERTY(bool swapAxis READ swapAxis NOTIFY settingsChanged)

public:
    static bool isDebugMode;
    explicit Backend(QObject *parent = nullptr);
    ~Backend();

    QStringList screenList() const;
    QVariantList screenGeometries() const; // <--- Getter
    QString connectionStatus() const;
    bool isConnected() const;
    QStringList usbDevices() const;

    bool isWifiRunning() const;
    
    // Settings Getters
    int pressureSensitivity() const;
    int minPressure() const;
    bool swapAxis() const;

public slots:
    void refreshScreens();
    void refreshUsbDevices();
    void selectScreen(int index);
    void connectDevice(int deviceIndex);
    void disconnectDevice();
    
    // --- UPDATED: Settings Logic ---
    void setPressureSensitivity(int value);
    void setMinPressure(int value);
    void setSwapAxis(bool swap);
    
    // --- NEW: Restored Missing Functions ---
    void toggleWifi(); // Changed to simple toggle
    void toggleDebug(bool enable);
    void resetDefaults();

signals:
    void screenListChanged();
    void connectionStatusChanged();
    void isConnectedChanged();
    void usbDevicesChanged();
    void wifiStatusChanged(); // New signal for Wi-Fi status
    void settingsChanged(); // New signal for UI updates

private:
    VirtualStylus *m_stylus;
    DisplayScreenTranslator *m_displayTranslator;
    PressureTranslator *m_pressureTranslator;
    
    QVector<QRect> m_screenRects; // Native rects
    QVariantList m_screenGeometriesVariant; // QML friendly list
    QStringList m_screenNames;
    QStringList m_usbDeviceIds;
    QStringList m_usbDeviceNames;
    
    QString m_status;
    bool m_connected;
    bool m_wifiRunning; // State tracker
    
    // Settings State
    int m_pressureSensitivity;
    int m_minPressure;
    bool m_swapAxis;

    void updateStatus(QString msg, bool connected);
};

#endif // BACKEND_H