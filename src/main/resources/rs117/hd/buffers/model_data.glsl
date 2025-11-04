#pragma once

struct ModelData {
    int flags;
};

#if ZONE_RENDERER
    uniform isamplerBuffer modelDataBuffer;

    #include MODEL_DATA_GETTER

    ModelData getModelData(int idx) {
        ModelData ret;
        if(idx <= 0) {
            return ret;
        }
        ret  = MODEL_DATA_GETTER(idx, modelDataBuffer);
        return ret;
    }
#else
    ModelData getModelData(int idx) {
        ModelData ret;
        return ret;
    }
#endif
