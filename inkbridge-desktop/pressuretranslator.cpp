#include "pressuretranslator.h"
#include "constants.h"

PressureTranslator::PressureTranslator() {}

#include <cmath> // Add this at the top

int PressureTranslator::getResultingPressure(AccessoryEventData * accessoryEventData){
    float rawPressure = accessoryEventData->pressure;
    float minThreshold = (float)minPressure / 100.0f;

    if (rawPressure <= minThreshold) return 0;

    // 1. Normalize pressure above the threshold
    float normalized = (rawPressure - minThreshold) / (1.0f - minThreshold);

    // 2. Apply Sensitivity multiplier
    float sensitivityFactor = (float)sensitivity / 50.0f;
    
    // 3. LOGARITHMIC CURVE: This makes light touches feel more responsive
    // Formula: output = input ^ (1 / sensitivity)
    // If sensitivity is high (e.g., 2.0), we reach max pressure faster.
    float curvedPressure = std::pow(normalized, 1.0f / sensitivityFactor);

    // 4. Clamp and Scale
    if (curvedPressure > 1.0f) curvedPressure = 1.0f;
    return static_cast<int>(curvedPressure * ABS_MAX_VAL);
}

float PressureTranslator::getPressureSensitivityPercentage(){
    return sensitivity / 50.0;
}


