#ifndef VIRTUALSTYLUS_H
#define VIRTUALSTYLUS_H
#include "accessory.h"
#include "displayscreentranslator.h"
#include "pressuretranslator.h"
#include <QScreen>
#include <QRect> 

class VirtualStylus
{
public:
    VirtualStylus(DisplayScreenTranslator * accessoryScreen, PressureTranslator *pressureTranslator);
    void handleAccessoryEventData(AccessoryEventData * accessoryEventData);
    void initializeStylus();
    void destroyStylus();

    // --- NEW FUNCTIONS ---
    void setTargetScreen(QRect geometry); 
    void setTotalDesktopGeometry(QRect geometry); // <--- ADD THIS
    void setInputResolution(int width, int height); 
    // ---------------------

    bool swapAxis = false;

private:
    int fd;
    bool isPenActive;
    DisplayScreenTranslator * displayScreenTranslator;
    PressureTranslator * pressureTranslator;
    void displayEventDebugInfo(AccessoryEventData * accessoryEventData);
    
    // --- VARIABLES ---
    QRect targetScreenGeometry; 
    QRect totalDesktopGeometry; // <--- ADD THIS
    int inputWidth = 0;
    int inputHeight = 0;
};
#endif // VIRTUALSTYLUS_H