#include <QApplication>
#include <iostream>
#include "mainwindow.h"
#include "protocol.h"

// SAFETY CHECK: Stops build if struct padding is incorrect.
static_assert(sizeof(PenPacket) == 14, "FATAL ERROR: PenPacket size is not 14 bytes. Check struct packing!");

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);

    // Modern C++: Use range-based for loop instead of Qt's 'foreach' macro
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