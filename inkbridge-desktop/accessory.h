#ifndef ACCESSORY_H
#define ACCESSORY_H
#include <string>
#include <array>
using namespace std;

typedef struct _accessoryEventData{
    int toolType;
    int action;
    float pressure;
    int x;
    int y;
} AccessoryEventData;
bool parseAccessoryEventDataLine(const string &line, AccessoryEventData * accessoryEventData);

#endif // ACCESSORY_H
