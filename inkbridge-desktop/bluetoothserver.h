#ifndef BLUETOOTHSERVER_H
#define BLUETOOTHSERVER_H

#include <QObject>
#include <QBluetoothServer>
#include <QBluetoothSocket>
#include <QBluetoothAddress>
#include <QBluetoothUuid>
#include <QByteArray>

/**
 * BluetoothServer
 *
 * Opens an RFCOMM server socket using Qt Bluetooth and waits for the
 * Android client to connect. Once connected it reads the incoming byte
 * stream and emits dataReceived() for every chunk of data, exactly as
 * WifiServer does — so Backend can use an identical handler.
 *
 * The well-known SPP UUID is used so the Android client can find the
 * service without any manual configuration. This UUID must match the
 * one in BluetoothStreamService.kt on the Android side.
 *
 * Threading: QBluetoothServer and QBluetoothSocket are event-driven and
 * live on the Qt event loop thread. No manual threading is needed here —
 * Qt handles async I/O internally, unlike the USB path which is blocking.
 */
class BluetoothServer : public QObject
{
    Q_OBJECT

public:
    explicit BluetoothServer(QObject *parent = nullptr);
    ~BluetoothServer();

    // Starts listening for incoming RFCOMM connections.
    // Returns true if the server socket was opened successfully.
    bool startServer();

    // Closes the server and disconnects any active client.
    void stopServer();

    bool isRunning() const;
    bool isClientConnected() const;

    // The SPP UUID — must match BluetoothStreamService.kt on Android.
    static const QBluetoothUuid SPP_UUID;

signals:
    // Emitted whenever raw bytes arrive from the Android client.
    // Backend connects this to its handleBluetoothData() slot,
    // identical to the WifiServer → handleWifiData() pattern.
    void dataReceived(QByteArray data);

    // Emitted when a client connects, with its Bluetooth address as a string.
    void clientConnected(QString address);

    // Emitted when the active client disconnects.
    void clientDisconnected();

    // Emitted if the server encounters an unrecoverable error.
    void serverError(QString message);

private slots:
    void onClientConnected();
    void onClientDisconnected();
    void onDataReady();
    void onSocketError(QBluetoothSocket::SocketError error);

private:
    QBluetoothServer  *m_server  = nullptr;
    QBluetoothSocket  *m_socket  = nullptr;  // The currently connected client
    bool               m_running = false;
};

#endif // BLUETOOTHSERVER_H
