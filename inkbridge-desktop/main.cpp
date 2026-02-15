#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QDir>
#include "backend.h"

int main(int argc, char *argv[])
{
    // High DPI scaling for modern 4K/Laptop screens
#if QT_VERSION < QT_VERSION_CHECK(6, 0, 0)
    QCoreApplication::setAttribute(Qt::AA_EnableHighDpiScaling);
#endif

    QGuiApplication app(argc, argv);

    // Register our C++ backend
    Backend backend;

    QQmlApplicationEngine engine;
    
    // Inject backend into QML
    engine.rootContext()->setContextProperty("backend", &backend);

    // --- Path Logic for Docker/Linux ---
    // Look for Main.qml in the same directory as the executable
    QString qmlPath = QCoreApplication::applicationDirPath() + "/Main.qml";
    QUrl url = QUrl::fromLocalFile(qmlPath);

    // Connect the safety signal to catch if the QML fails to load (e.g., missing module)
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreated,
                     &app, [url](QObject *obj, const QUrl &objUrl) {
        if (!obj && url == objUrl)
            QCoreApplication::exit(-1);
    }, Qt::QueuedConnection);
    
    // Load the UI
    engine.load(url);

    return app.exec();
}