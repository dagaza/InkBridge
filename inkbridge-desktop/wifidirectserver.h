#ifndef WIFIDIRECTSERVER_H
#define WIFIDIRECTSERVER_H

#include <QObject>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUdpSocket>
#include <QTimer>

/**
 * WifiDirectServer — manual setup flow
 *
 * The desktop does NOT attempt to manage the WiFi connection itself.
 * Instead:
 *   1. startServer() opens the UDP beacon listener on BEACON_PORT
 *   2. When Android's beacon arrives, credentials are extracted and
 *      emitted via credentialsReceived() for display in the UI
 *   3. The TCP server opens immediately so it's ready when the user
 *      manually connects the desktop WiFi and Android scans for it
 *   4. Android finds the TCP server at 192.168.49.x and connects
 *
 * Signals are identical to WifiServer so Backend wiring is unchanged.
 */
class WifiDirectServer : public QObject
{
    Q_OBJECT

public:
    explicit WifiDirectServer(QObject *parent = nullptr);
    ~WifiDirectServer();

    bool startServer();
    void stopServer();
    bool isRunning() const;
    bool isClientConnected() const;

    static constexpr quint16 DATA_PORT   = 4545;
    static constexpr quint16 BEACON_PORT = 4547;
    static const     QString BEACON_PREFIX;

signals:
    void dataReceived(QByteArray data);
    void clientConnected(QString clientIp);
    void clientDisconnected();
    void serverError(QString message);
    void statusChanged(QString message);

    // Emitted when Android beacon arrives — UI shows these to the user
    // so they know which network to connect to on their desktop.
    void credentialsReceived(QString ssid, QString passphrase);

private slots:
    void onBeaconReceived();
    void onNewTcpConnection();
    void onTcpDataReady();
    void onTcpClientDisconnected();

private:
    QUdpSocket *m_beaconSocket  = nullptr;
    QTcpServer *m_tcpServer     = nullptr;
    QTcpSocket *m_clientSocket  = nullptr;
    bool        m_running       = false;

    bool startTcpServer();
};

#endif // WIFIDIRECTSERVER_H