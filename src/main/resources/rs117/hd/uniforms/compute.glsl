#pragma once

#include MAX_CHARACTER_POSITION_COUNT

layout(std140) uniform UBOCompute {
    // Camera uniforms
    float cameraYaw;
    float cameraPitch;
    int centerX;
    int centerY;
    int zoom;
    float cameraX; float cameraY; float cameraZ; // Here be dragons on macOS if converted to vec3

    // Wind uniforms
    float windDirectionX;
    float windDirectionZ;
    float windStrength;
    float windCeiling;
    float windOffset;

    int characterPositionCount;
    vec3 characterPositions[MAX_CHARACTER_POSITION_COUNT];
};
