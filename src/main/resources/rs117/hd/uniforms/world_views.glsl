#pragma once

#include <utils/constants.glsl>

#if ZONE_RENDERER
    #include MAX_SIMULTANEOUS_WORLD_VIEWS

    struct WorldView {
        mat4 projectionMatrix;
        ivec4 tint;
    };

    layout(std140) uniform UBOWorldViews {
        WorldView WorldViewArray[MAX_SIMULTANEOUS_WORLD_VIEWS];
    };

    #include WORLD_VIEW_GETTER

    mat4 getWorldViewProjection(int worldViewIndex) {
        if (worldViewIndex <= 0)
            return mat4(1);
        return getWorldView(worldViewIndex - 1).projectionMatrix;
    }

    ivec4 getWorldViewTint(int worldViewIndex) {
        if (worldViewIndex <= 0)
            return ivec4(-1);
        return getWorldView(worldViewIndex - 1).tint;
    }
#else
    #define getWorldViewProjection(worldViewIndex) mat4(1)
    #define getWorldViewTint(worldViewIndex) ivec4(0)
#endif
