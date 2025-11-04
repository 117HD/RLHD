#pragma once

struct ModelData {
    ivec3 position;
    int height;
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
        ret = MODEL_DATA_GETTER(idx - 1, modelDataBuffer);
        return ret;
    }
#else
    ModelData getModelData(int idx) {
        ModelData ret;
        return ret;
    }
#endif
