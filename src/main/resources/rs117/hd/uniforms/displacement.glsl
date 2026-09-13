#pragma once

#include <utils/misc.glsl>

#include MAX_CHARACTER_POSITION_COUNT

layout(std140) uniform UBODisplacement {
    float windDirectionX;
    float windDirectionZ;
    float windStrength;
    float windCeiling;
    float windOffset;

    int characterPositionCount;
    vec4 characterPositions[MAX_CHARACTER_POSITION_COUNT];
};
