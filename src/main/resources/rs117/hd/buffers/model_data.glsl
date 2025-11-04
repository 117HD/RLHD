#pragma once

#include <utils/constants.glsl>

#if ZONE_RENDERER
    struct ModelData {
        int flags;
    };

    uniform usampler1D shadowMap;

    // TODO: make TextureStructuredBuffer generate a getter!
#endif
