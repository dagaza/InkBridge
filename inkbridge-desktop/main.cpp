#include <QApplication>
#include <iostream>
#include "mainwindow.h"
#include "protocol.h"

// SAFETY CHECK: This stops the build immediately if the struct is padded.
// UPDATE: Expecting 22 bytes to account for Tilt X and Tilt Y
static_assert(sizeof(PenPacket) == 22, "FATAL ERROR: PenPacket size is not 22 bytes. Check struct packing!");

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);

    for (const QString &arg : app.arguments()) {
        if (arg == "-d") {
            MainWindow::isDebugMode = true;
        }
    }

    if (MainWindow::isDebugMode) {
        std::cout << "DEBUG: Protocol Check OK. PenPacket size is " 
                  << sizeof(PenPacket) << " bytes." << std::endl;
    }

    MainWindow w;
    w.show();

    return app.exec();
}