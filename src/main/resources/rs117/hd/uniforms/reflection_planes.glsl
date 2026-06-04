#pragma once

#include <utils/camera.glsl>

#include MAX_REFLECTION_RENDERS
#include WATER_HEIGHT_THRESHOLD

struct ReflectionPlane {
    Camera camera;
    float height;
};

layout(std140) uniform UBOReflectionPlanes {
    ReflectionPlane planes[MAX_REFLECTION_RENDERS];
    int activePlanes;
    vec4 cullingPlane;
};

int findClosestPlane(float height) {
    for (int i = 0; i < activePlanes; i++)
        if (distance(planes[i].height, height) <= WATER_HEIGHT_THRESHOLD)
            return i;
    return -1;
}
