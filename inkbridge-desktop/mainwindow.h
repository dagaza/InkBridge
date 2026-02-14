#ifndef MAINWINDOW_H
#define MAINWINDOW_H

#include <QComboBox>  // <--- Add this include at the top
#include <QScreen>    // <--- Add this include at the top
#include <QRect>      // <--- Add this include at the top
#include "virtualstylus.h" // <--- CRITICAL: Make sure this include is here!
#include <QVector> // <--- Add this include for QVector
#include <QListWidgetItem>
#include <QMainWindow>
#include <QMap>
#include <QMessageBox>
#include <QSettings>
#include <QIntValidator>
#include <QDialog>
#include <QLineEdit>
#include <QtConcurrent/QtConcurrent>
#include <atomic>
#include "displayscreentranslator.h"
#include "filepermissionvalidator.h"
#include "pressuretranslator.h"

using namespace std;
QT_BEGIN_NAMESPACE
namespace Ui {
class MainWindow;
}
QT_END_NAMESPACE



class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    static bool isDebugMode;
    MainWindow(QWidget *parent = nullptr);
    void captureStylusInput();
    ~MainWindow();

private slots:

    void on_usbDevicesListWidget_itemClicked(QListWidgetItem *item);

    void on_connectUsbButton_clicked();

    void on_deviceXSize_editingFinished();

    void on_deviceYSize_editingFinished();

    void on_displayStyleComboBox_currentIndexChanged(int index);

    void on_pressureSensitivitySlider_valueChanged(int value);

    void on_minimumPressureSlider_valueChanged(int value);

    void on_refreshUsbDevices_clicked();

    void on_deviceXSize_selectionChanged();

    void on_deviceYSize_selectionChanged();

    void on_connectUsbButton_2_clicked();

    void on_startWifiButton_clicked();

    void on_wifiPortInput_editingFinished();

    void on_monitorChanged(int index); // <--- Add this new slot definition

private:
    const QString setting_org = "com.github.inkbridge";
    const QString setting_app = "InkBridge";
    const string y_device_setting_key = "/y_size";
    const string x_device_setting_key = "/x_size";
    const string min_pressure_setting_key = "/min_pressure";
    const string pressure_sensitivity_setting_key = "/pressure_sensitivity";
    const string display_style_setting_key = "/display_style";
    const string wifi_port_setting_key = "/wifi_port";
    QDialog * dialog;
    FilePermissionValidator * filePermissionValidator;
    const int max_device_size = 999999999;

    Ui::MainWindow *ui;
    VirtualStylus *virtualStylus;
    QComboBox *monitorSelector;        // <--- The dropdown box
    QVector<QRect> screenGeometries;   // <--- Stores the math for each screen
    QMessageBox * messageBox;
    QSettings * settings;
    DisplayScreenTranslator * displayScreenTranslator;
    PressureTranslator * pressureTranslator;
    QMap<string, string>* usbDevices;
    int libUsbContext;
    string selectedDeviceIdentifier;
    string selectedDevice;
    QVariant getSetting(string settingKey);
    QVariant getSetting(string settingKey, QVariant defaultValue);
    void initDisplayStyles();
    void setSetting(string settingKey, QVariant value);
    void fetchUsbDevices();
    void populateUsbDevicesList();
    bool canConnectUsb();
    bool canStartWifi();
    void updateUsbConnectButton();
    void captureWifiInput(int port);
    void loadDeviceConfig();
    void manageInputBoxStyle(QLineEdit * inputBox);
    void displayUDevPermissionFixIfNeeded();
    void displayFixForUDevPermissions();
    bool canWriteToFile(QString path);
    bool canWriteToAnyUsbDevice();
    std::atomic_bool wifiRunning{false};
};
#endif // MAINWINDOW_H
