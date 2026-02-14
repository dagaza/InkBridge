#include <QApplication>
#include <iostream>
#include "mainwindow.h"
#include "protocol.h"

// SAFETY CHECK: This stops the build immediately if the struct is padded.
static_assert(sizeof(PenPacket) == 14, "FATAL ERROR: PenPacket size is not 14 bytes. Check struct packing!");

int main(int argc, char *argv[])
{
    QApplication a(argc, argv);

    foreach (QString arg, a.arguments()) {
        if(arg == "-d"){
            MainWindow::isDebugMode = true;
        }
    }

    // Runtime debug print (visible in the console)
    if (MainWindow::isDebugMode) {
        std::cout << "DEBUG: Protocol Check OK. PenPacket size is " 
                  << sizeof(PenPacket) << " bytes." << std::endl;
    }

    MainWindow w;
    w.show();
    return a.exec();
}