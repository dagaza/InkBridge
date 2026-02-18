#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QIcon>  // Required for the icon
#include "backend.h"

int main(int argc, char *argv[])
{
    // High DPI scaling for modern 4K/Laptop screens
#if QT_VERSION < QT_VERSION_CHECK(6, 0, 0)
    QCoreApplication::setAttribute(Qt::AA_EnableHighDpiScaling);
#endif

    QGuiApplication app(argc, argv);

    // --- NEW: SET WINDOW ICON ---
    // This looks inside the binary's resources (assets.qrc)
    app.setWindowIcon(QIcon(":/assets/inkbridge.png"));

    // Register our C++ backend
    Backend backend;

    QQmlApplicationEngine engine;
    
    // Inject backend into QML
    engine.rootContext()->setContextProperty("backend", &backend);

    // --- IMPROVED: RESOURCE PATH LOGIC ---
    // Instead of looking for a local file, we look inside the compiled resources.
    // This makes the binary standalone.
    const QUrl url(QStringLiteral("qrc:/Main.qml"));

    QObject::connect(&engine, &QQmlApplicationEngine::objectCreated,
                     &app, [url](QObject *obj, const QUrl &objUrl) {
        if (!obj && url == objUrl)
            QCoreApplication::exit(-1);
    }, Qt::QueuedConnection);
    
    engine.load(url);

    return app.exec();
}