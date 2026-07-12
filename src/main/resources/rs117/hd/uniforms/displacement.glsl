#pragma once

#include MAX_CHARACTER_POSITION_COUNT
#include MAX_BOAT_COUNT

struct Boat {
    vec4 corners01;      // c0.xy c1.xy
    vec4 corner2Height;  // c2.xy height padding
};

layout(std140) uniform UBODisplacement {
    float windDirectionX;
    float windDirectionZ;
    float windStrength;
    float windCeiling;
    float windOffset;

    int characterPositionCount;
    int boatCount;

    vec3 characterPositions[MAX_CHARACTER_POSITION_COUNT];
    Boat boatData[MAX_BOAT_COUNT];
};

float boatDistance(vec2 p, Boat boat) {
    vec2 c0 = boat.corners01.xy;
    vec2 c1 = boat.corners01.zw;
    vec2 c2 = boat.corner2Height.xy;
    vec2 c3 = c0 + (c2 - c1);

    vec2 axisX = normalize(c1 - c0);
    vec2 axisZ = normalize(c3 - c0);

    float width = length(c1 - c0);
    float depth = length(c3 - c0);

    vec2 rel = p - c0;
    float x = dot(rel, axisX);
    float z = dot(rel, axisZ);

    float dx = max(max(-x, x - width), 0.0);
    float dz = max(max(-z, z - depth), 0.0);
    return length(vec2(dx, dz));
}