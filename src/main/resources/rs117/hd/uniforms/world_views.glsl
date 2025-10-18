#pragma once

#if ZONE_RENDERER
    #include WORLD_VIEW_COUNT

    struct WorldView {
        mat4 projectionMatrix;
        ivec4 tint;
    };

    layout(std140) uniform UBOWorldViews {
        WorldView WorldViewArray[WORLD_VIEW_COUNT];
    };

    #include WORLD_VIEW_GETTER

    mat4 getWorldViewProjection(int worldViewId) {
        if (worldViewId == -1)
            return mat4(1);
        return getWorldView(worldViewId).projectionMatrix;
    }

    ivec4 getWorldViewTint(int worldViewId) {
        if (worldViewId == -1)
            return ivec4(0);
        return getWorldView(worldViewId).tint;
    }
#else
    #define getWorldViewProjection(worldViewId) mat4(1)
    #define getWorldViewTint(worldViewId) ivec4(0)
#endif
