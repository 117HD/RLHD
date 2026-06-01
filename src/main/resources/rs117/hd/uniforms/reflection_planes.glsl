#pragma once

#include <utils/camera.glsl>

// TODO: Move to Constants
#define MAX_REFLECTION_RENDERS 4
#define WATER_HEIGHT_THRESHOLD 128

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
    for(int i = 0; i < activePlanes; i++) {
        if(planes[i].height - height <= WATER_HEIGHT_THRESHOLD) {
            return i;
        }
    }

    return -1;
}
