#pragma once

#include MAX_CHARACTER_POSITION_COUNT

layout(std140) uniform UBODisplacement {
    // Wind uniforms
    float windDirectionX;
    float windDirectionZ;
    float windStrength;
    float windCeiling;
    float windOffset;

    int characterPositionCount;
    vec3 characterPositions[MAX_CHARACTER_POSITION_COUNT];
};
