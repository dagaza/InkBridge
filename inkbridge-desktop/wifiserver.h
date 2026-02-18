#ifndef WIFISERVER_H
#define WIFISERVER_H

#include <QObject>
#include <QUdpSocket>
#include <QTcpServer>
#include <QTcpSocket>
#include <QNetworkInterface>

class WifiServer : public QObject
{
    Q_OBJECT
public:
    explicit WifiServer(QObject *parent = nullptr);
    ~WifiServer();

    bool startServer(quint16 discoveryPort = 4546, quint16 dataPort = 4545);
    void stopServer();
    bool isRunning() const;

signals:
    // Emitted when raw touch data arrives from the tablet
    void dataReceived(QByteArray data);
    // Emitted to update the UI (e.g., "Tablet Connected via Wi-Fi")
    void clientConnected(QString clientIp);
    void clientDisconnected();

private slots:
    void processBroadcast();   // UDP "Hello" handler
    void handleNewConnection(); // TCP Accept handler
    void readTcpData();         // TCP Data handler
    void handleClientDisconnect();

private:
    QUdpSocket *m_udpSocket;
    QTcpServer *m_tcpServer;
    QTcpSocket *m_clientSocket;
    bool m_running;
};

#endif // WIFISERVER_H