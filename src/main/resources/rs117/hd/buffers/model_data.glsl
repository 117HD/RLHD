#pragma once

struct ModelData {
    int flags;
};

#if ZONE_RENDERER
    uniform isampler1D modelDataBuffer;

    #include MODEL_DATA_GETTER

    ModelData getModelData(int idx) {
        return MODEL_DATA_GETTER(idx, modelDataBuffer);
    }
#else
    ModelData getModelData(int idx) {
        ModelData ret;
        return ret;
    }
#endif
