#pragma once

#include <utils/constants.glsl>

#if ZONE_RENDERER
    struct ModelData {
        int flags;
    };

    uniform isampler1D modelData;

    #include MODEL_DATA_GETTER
#else
#endif
