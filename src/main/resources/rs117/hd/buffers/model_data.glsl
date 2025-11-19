#pragma once

struct ModelData {
    ivec3 position;
    int height;
    int flags;
    int worldViewId;
};

#if ZONE_RENDERER
    #include MODEL_DATA_GETTER

    ModelData getModelData(int idx) {
        ModelData ret;
        if (idx > 0) {
            ret = MODEL_DATA_GETTER(idx - 1);
        } else {
            ret = ModelData(ivec3(0), 0, 0, 0);
        }
        return ret;
    }
#else
    ModelData getModelData(int idx) {
        return ModelData(ivec3(0), 0, 0, 0);
    }
#endif

bool isStaticModel(ModelData data) { return (data.flags & 1) == 1; }
bool isDetailModel(ModelData data) { return ((data.flags >> 1) & 1) == 1; }
