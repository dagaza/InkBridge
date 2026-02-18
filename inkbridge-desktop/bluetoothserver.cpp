#include "bluetoothserver.h"
#include <QBluetoothLocalDevice>
#include <QDebug>

// Standard SPP UUID — must match BluetoothStreamService.kt on Android.
const QBluetoothUuid BluetoothServer::SPP_UUID =
    QBluetoothUuid(QString("00001101-0000-1000-8000-00805F9B34FB"));

BluetoothServer::BluetoothServer(QObject *parent)
    : QObject(parent)
{
}

BluetoothServer::~BluetoothServer() {
    stopServer();
}

bool BluetoothServer::startServer() {
    if (m_running) {
        qDebug() << "[BT Server] Already running, ignoring start request.";
        return true;
    }

    // Check that the local adapter exists and is powered on.
    QBluetoothLocalDevice localDevice;
    if (!localDevice.isValid()) {
        emit serverError("No Bluetooth adapter found on this machine.");
        return false;
    }
    if (localDevice.hostMode() == QBluetoothLocalDevice::HostPoweredOff) {
        emit serverError("Bluetooth adapter is powered off. Please enable Bluetooth.");
        return false;
    }

    // Make the adapter discoverable so the Android client can find it,
    // but only while the server is actively listening.
    localDevice.setHostMode(QBluetoothLocalDevice::HostDiscoverable);

    // Create the RFCOMM server socket.
    m_server = new QBluetoothServer(QBluetoothServiceInfo::RfcommProtocol, this);

    connect(m_server, &QBluetoothServer::newConnection,
            this,     &BluetoothServer::onClientConnected);

    // Listen on the SPP UUID. Qt registers the SDP service record
    // automatically, so Android can discover it by UUID without needing
    // a hardcoded channel number.
    if (!m_server->listen(localDevice.address())) {
        emit serverError("Failed to open RFCOMM server socket. "
                         "Check that Bluetooth is enabled and no other "
                         "app is using the SPP channel.");
        delete m_server;
        m_server = nullptr;
        return false;
    }

    // Register the SDP service record so the Android SPP client can
    // locate us by the standard UUID during discovery.
    QBluetoothServiceInfo serviceInfo;
    serviceInfo.setAttribute(QBluetoothServiceInfo::ServiceName,
                             QVariant(QString("InkBridge")));
    serviceInfo.setAttribute(QBluetoothServiceInfo::ServiceDescription,
                             QVariant(QString("InkBridge stylus input stream")));
    serviceInfo.setAttribute(QBluetoothServiceInfo::ServiceProvider,
                             QVariant(QString("inkbridge")));

    // Map the SPP UUID to this server's RFCOMM channel.
    QBluetoothServiceInfo::Sequence classId;
    classId << QVariant::fromValue(SPP_UUID);
    serviceInfo.setAttribute(QBluetoothServiceInfo::ServiceClassIds, classId);
    serviceInfo.setServiceUuid(SPP_UUID);

    QBluetoothServiceInfo::Sequence protocolList;
    QBluetoothServiceInfo::Sequence protocol;
    protocol << QVariant::fromValue(QBluetoothUuid(QBluetoothUuid::ProtocolUuid::L2cap));
    protocolList.append(QVariant::fromValue(protocol));
    protocol.clear();
    protocol << QVariant::fromValue(QBluetoothUuid(QBluetoothUuid::ProtocolUuid::Rfcomm))
             << QVariant::fromValue(quint8(m_server->serverPort()));
    protocolList.append(QVariant::fromValue(protocol));
    serviceInfo.setAttribute(QBluetoothServiceInfo::ProtocolDescriptorList, protocolList);
    serviceInfo.registerService(localDevice.address());

    m_running = true;
    qDebug() << "[BT Server] Listening on RFCOMM channel" << m_server->serverPort()
             << "| Address:" << localDevice.address().toString();

    return true;
}

void BluetoothServer::stopServer() {
    if (!m_running) return;

    qDebug() << "[BT Server] Stopping...";
    m_running = false;

    // Disconnect and destroy the active client socket first.
    if (m_socket) {
        m_socket->disconnectFromService();
        m_socket->deleteLater();
        m_socket = nullptr;
    }

    // Close the server socket.
    if (m_server) {
        m_server->close();
        m_server->deleteLater();
        m_server = nullptr;
    }

    // Return adapter to connectable (not discoverable) mode.
    QBluetoothLocalDevice localDevice;
    if (localDevice.isValid()) {
        localDevice.setHostMode(QBluetoothLocalDevice::HostConnectable);
    }

    qDebug() << "[BT Server] Stopped.";
}

bool BluetoothServer::isRunning() const {
    return m_running;
}

bool BluetoothServer::isClientConnected() const {
    return m_socket != nullptr &&
           m_socket->state() == QBluetoothSocket::SocketState::ConnectedState;
}

// ---------------------------------------------------------------------------
// Private slots
// ---------------------------------------------------------------------------

void BluetoothServer::onClientConnected() {
    if (!m_server) return;

    // If a client is already connected, reject the new one. Only one
    // Android device should be driving input at a time.
    QBluetoothSocket *pending = m_server->nextPendingConnection();
    if (!pending) return;

    if (m_socket && m_socket->state() == QBluetoothSocket::SocketState::ConnectedState) {
        qDebug() << "[BT Server] Second client rejected — already have an active connection.";
        pending->disconnectFromService();
        pending->deleteLater();
        return;
    }

    // Accept the connection.
    m_socket = pending;
    m_socket->setParent(this);

    connect(m_socket, &QBluetoothSocket::readyRead,
            this,     &BluetoothServer::onDataReady);
    connect(m_socket, &QBluetoothSocket::disconnected,
            this,     &BluetoothServer::onClientDisconnected);
    // QBluetoothSocket::errorOccurred was introduced in Qt 6.2.
    // For compatibility with Qt 5 and early Qt 6, we use a version guard.
#if QT_VERSION >= QT_VERSION_CHECK(6, 2, 0)
    connect(m_socket, &QBluetoothSocket::errorOccurred,
            this,     &BluetoothServer::onSocketError);
#else
    connect(m_socket, QOverload<QBluetoothSocket::SocketError>::of(&QBluetoothSocket::error),
            this,     &BluetoothServer::onSocketError);
#endif

    QString address = m_socket->peerAddress().toString();
    qDebug() << "[BT Server] Client connected:" << address;
    emit clientConnected(address);
}

void BluetoothServer::onClientDisconnected() {
    qDebug() << "[BT Server] Client disconnected.";

    if (m_socket) {
        m_socket->deleteLater();
        m_socket = nullptr;
    }

    emit clientDisconnected();
}

void BluetoothServer::onDataReady() {
    if (!m_socket) return;

    // Read all available bytes and emit them. Backend's handleBluetoothData()
    // handles the packet framing, exactly as handleWifiData() does.
    // We loop in case multiple chunks are queued.
    while (m_socket->bytesAvailable() > 0) {
        QByteArray data = m_socket->readAll();
        if (!data.isEmpty()) {
            emit dataReceived(data);
        }
    }
}

void BluetoothServer::onSocketError(QBluetoothSocket::SocketError error) {
    QString msg = m_socket ? m_socket->errorString() : "Unknown socket error";
    qDebug() << "[BT Server] Socket error" << error << ":" << msg;

    // Treat any socket error as a disconnect — the client will reconnect.
    onClientDisconnected();
    emit serverError(msg);
}