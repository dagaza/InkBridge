#include "wifiserver.h"
#include <QDebug>
#include <QNetworkDatagram>

WifiServer::WifiServer(QObject *parent)
    : QObject(parent)
    , m_udpSocket(nullptr)
    , m_tcpServer(nullptr)
    , m_clientSocket(nullptr)
    , m_running(false)
{
}

WifiServer::~WifiServer() {
    stopServer();
}

bool WifiServer::startServer(quint16 discoveryPort, quint16 dataPort) {
    if (m_running) return true;

    // 1. Setup UDP Discovery (The "Ears")
    m_udpSocket = new QUdpSocket(this);
    // Bind to all interfaces. ShareAddress allows other apps to bind too (good practice).
    if (!m_udpSocket->bind(QHostAddress::Any, discoveryPort, QUdpSocket::ShareAddress)) {
        qCritical() << "[WiFi] Failed to bind UDP port" << discoveryPort;
        return false;
    }
    connect(m_udpSocket, &QUdpSocket::readyRead, this, &WifiServer::processBroadcast);

    // 2. Setup TCP Server (The "Pipe")
    m_tcpServer = new QTcpServer(this);
    if (!m_tcpServer->listen(QHostAddress::Any, dataPort)) {
        qCritical() << "[WiFi] Failed to start TCP server on port" << dataPort;
        stopServer();
        return false;
    }
    connect(m_tcpServer, &QTcpServer::newConnection, this, &WifiServer::handleNewConnection);

    m_running = true;
    qDebug() << "[WiFi] Server Started. Listening on UDP" << discoveryPort << "and TCP" << dataPort;
    return true;
}

void WifiServer::stopServer() {
    m_running = false;

    if (m_udpSocket) {
        m_udpSocket->close();
        m_udpSocket->deleteLater();
        m_udpSocket = nullptr;
    }

    if (m_clientSocket) {
        m_clientSocket->disconnectFromHost();
        m_clientSocket->deleteLater();
        m_clientSocket = nullptr;
    }

    if (m_tcpServer) {
        m_tcpServer->close();
        m_tcpServer->deleteLater();
        m_tcpServer = nullptr;
    }
    
    qDebug() << "[WiFi] Server Stopped.";
}

bool WifiServer::isRunning() const {
    return m_running;
}

// --- UDP DISCOVERY LOGIC ---
void WifiServer::processBroadcast() {
    while (m_udpSocket && m_udpSocket->hasPendingDatagrams()) {
        QNetworkDatagram datagram = m_udpSocket->receiveDatagram();
        QByteArray payload = datagram.data();
        QString message = QString::fromUtf8(payload).trimmed();

        // Must match the Android string exactly!
        if (message == "INKBRIDGE_DISCOVER") {
            QByteArray reply = "I_AM_INKBRIDGE";
            
            // --- FIX: Normalize Address to IPv4 ---
            QHostAddress sender = datagram.senderAddress();
            
            // If we see an IPv6-mapped IPv4 address (e.g., ::ffff:192.168.1.5),
            // convert it to a pure IPv4 address (192.168.1.5) so Android accepts the reply.
            bool conversionOk = false;
            quint32 ipv4 = sender.toIPv4Address(&conversionOk);
            if (conversionOk) {
                sender.setAddress(ipv4);
            }
            // --------------------------------------

            m_udpSocket->writeDatagram(reply, sender, datagram.senderPort());
            qDebug() << "[WiFi] Discovery from" << sender.toString() << "- Replied.";
        }
    }
}

// --- TCP CONNECTION LOGIC ---
void WifiServer::handleNewConnection() {
    if (!m_tcpServer) return;

    // We only support ONE tablet at a time for simplicity
    if (m_clientSocket) {
        qDebug() << "[WiFi] Busy. Rejecting extra connection.";
        QTcpSocket *reject = m_tcpServer->nextPendingConnection();
        reject->disconnectFromHost();
        reject->deleteLater();
        return;
    }

    m_clientSocket = m_tcpServer->nextPendingConnection();
    QString clientIp = m_clientSocket->peerAddress().toString();
    
    connect(m_clientSocket, &QTcpSocket::readyRead, this, &WifiServer::readTcpData);
    connect(m_clientSocket, &QTcpSocket::disconnected, this, &WifiServer::handleClientDisconnect);

    qDebug() << "[WiFi] Tablet Connected:" << clientIp;
    emit clientConnected(clientIp);
}

void WifiServer::readTcpData() {
    if (!m_clientSocket) return;
    
    // Read all available bytes. 
    // The VirtualStylus logic will handle parsing the raw stream.
    QByteArray data = m_clientSocket->readAll();
    emit dataReceived(data);
}

void WifiServer::handleClientDisconnect() {
    qDebug() << "[WiFi] Tablet Disconnected.";
    if (m_clientSocket) {
        m_clientSocket->deleteLater();
        m_clientSocket = nullptr;
    }
    emit clientDisconnected();
}