#ifndef VIRTUALSTYLUS_H
#define VIRTUALSTYLUS_H

#include <QObject> // <--- REQUIRED for QTimer and Q_OBJECT
#include <QTimer>  // <--- REQUIRED for the Watchdog
#include <QScreen>
#include <QRect> 
#include "accessory.h"
#include "displayscreentranslator.h"
#include "pressuretranslator.h"

// We must inherit from QObject to use Signals/Slots (Timers)
class VirtualStylus : public QObject
{
    Q_OBJECT // <--- Enable Qt meta-object features

public:
    // Constructor
    explicit VirtualStylus(DisplayScreenTranslator * accessoryScreen, 
                           PressureTranslator *pressureTranslator, 
                           QObject *parent = nullptr);

    void handleAccessoryEventData(AccessoryEventData * accessoryEventData);
    void initializeStylus();
    void destroyStylus();

    // --- GEOMETRY SETTERS ---
    void setTargetScreen(QRect geometry); 
    void setTotalDesktopGeometry(QRect geometry); 
    void setInputResolution(int width, int height); 

    bool swapAxis = false;

private slots:
    // --- SAFETY SLOT ---
    void onWatchdogTimeout(); // Called when the stream goes silent

private:
    int fd;
    bool isPenActive = false;
    
    // --- TIMER ---
    QTimer *watchdogTimer; 

    DisplayScreenTranslator * displayScreenTranslator;
    PressureTranslator * pressureTranslator;
    void displayEventDebugInfo(AccessoryEventData * accessoryEventData);
    
    // --- VARIABLES ---
    QRect targetScreenGeometry; 
    QRect totalDesktopGeometry;
    int inputWidth = 0;
    int inputHeight = 0;
};

#endif // VIRTUALSTYLUS_H