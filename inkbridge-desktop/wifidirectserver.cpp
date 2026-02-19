#include "wifidirectserver.h"
#include <QDebug>
#include <QNetworkDatagram>

const QString WifiDirectServer::BEACON_PREFIX = "INKBRIDGE_P2P:";

WifiDirectServer::WifiDirectServer(QObject *parent)
    : QObject(parent)
{
}

WifiDirectServer::~WifiDirectServer() {
    stopServer();
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

bool WifiDirectServer::startServer() {
    if (m_running) return true;

    m_beaconSocket = new QUdpSocket(this);
    if (!m_beaconSocket->bind(QHostAddress::AnyIPv4, BEACON_PORT, QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint)) {
        emit serverError(QString("Failed to bind UDP beacon port %1.").arg(BEACON_PORT));
        delete m_beaconSocket;
        m_beaconSocket = nullptr;
        return false;
    }

    connect(m_beaconSocket, &QUdpSocket::readyRead,
            this,           &WifiDirectServer::onBeaconReceived);

    m_running = true;
    emit statusChanged("WiFi Direct: Waiting for Android to create P2P group...");
    qDebug() << "[P2P] Beacon listener started on UDP port" << BEACON_PORT;
    return true;
}

void WifiDirectServer::stopServer() {
    if (!m_running) return;
    m_running = false;

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
    if (m_beaconSocket) {
        m_beaconSocket->close();
        m_beaconSocket->deleteLater();
        m_beaconSocket = nullptr;
    }

    qDebug() << "[P2P] Server stopped.";
}

bool WifiDirectServer::isRunning() const        { return m_running; }
bool WifiDirectServer::isClientConnected() const {
    return m_clientSocket &&
           m_clientSocket->state() == QAbstractSocket::ConnectedState;
}

// ---------------------------------------------------------------------------
// Beacon received — extract credentials, open TCP server, tell UI what to show
// ---------------------------------------------------------------------------

void WifiDirectServer::onBeaconReceived() {
    if (!m_beaconSocket) return;

    while (m_beaconSocket->hasPendingDatagrams()) {
        QNetworkDatagram datagram = m_beaconSocket->receiveDatagram();
        QString message = QString::fromUtf8(datagram.data()).trimmed();

        if (!message.startsWith(BEACON_PREFIX)) continue;

        QString payload = message.mid(BEACON_PREFIX.length());
        int sep = payload.indexOf(':');
        if (sep < 0) {
            qWarning() << "[P2P] Malformed beacon, ignoring.";
            continue;
        }

        QString ssid       = payload.left(sep);
        QString passphrase = payload.mid(sep + 1);

        qDebug() << "[P2P] Beacon received. SSID:" << ssid;

        // Open TCP server immediately — it must be ready before Android scans
        if (!m_tcpServer) {
            if (!startTcpServer()) {
                emit serverError("WiFi Direct: Could not open TCP data port.");
                return;
            }
        }

        // Tell the UI to show the credentials and the "Ready" button.
        // The user connects their desktop WiFi manually, then clicks Ready
        // in the desktop app — which causes Android's scanAndConnect() to
        // find our TCP server.
        emit credentialsReceived(ssid, passphrase);
        emit statusChanged(
            QString("Connect this PC's WiFi to:\n"
                    "Network:  %1\n"
                    "Password: %2\n"
                    "Then click 'I'm Connected' below.").arg(ssid, passphrase)
        );

        // Stop listening for further beacons — we have what we need.
        m_beaconSocket->close();
        break;
    }
}

// ---------------------------------------------------------------------------
// TCP server
// ---------------------------------------------------------------------------

bool WifiDirectServer::startTcpServer() {
    m_tcpServer = new QTcpServer(this);
    if (!m_tcpServer->listen(QHostAddress::Any, DATA_PORT)) {
        qCritical() << "[P2P] Failed to bind TCP port" << DATA_PORT;
        m_tcpServer->deleteLater();
        m_tcpServer = nullptr;
        return false;
    }
    connect(m_tcpServer, &QTcpServer::newConnection,
            this,        &WifiDirectServer::onNewTcpConnection);
    qDebug() << "[P2P] TCP server listening on port" << DATA_PORT;
    return true;
}

void WifiDirectServer::onNewTcpConnection() {
    if (!m_tcpServer) return;

    if (m_clientSocket) {
        QTcpSocket *rej = m_tcpServer->nextPendingConnection();
        rej->disconnectFromHost();
        rej->deleteLater();
        return;
    }

    m_clientSocket = m_tcpServer->nextPendingConnection();
    QString ip = m_clientSocket->peerAddress().toString();

    connect(m_clientSocket, &QTcpSocket::readyRead,
            this,           &WifiDirectServer::onTcpDataReady);
    connect(m_clientSocket, &QTcpSocket::disconnected,
            this,           &WifiDirectServer::onTcpClientDisconnected);

    qDebug() << "[P2P] Tablet connected from" << ip;
    emit clientConnected(ip);
}

void WifiDirectServer::onTcpDataReady() {
    if (!m_clientSocket) return;
    emit dataReceived(m_clientSocket->readAll());
}

void WifiDirectServer::onTcpClientDisconnected() {
    qDebug() << "[P2P] Tablet disconnected.";
    if (m_clientSocket) {
        m_clientSocket->deleteLater();
        m_clientSocket = nullptr;
    }
    emit clientDisconnected();
}