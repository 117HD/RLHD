#pragma once

#include <utils/texture_buffer_reader.glsl>

#define MODEL_DATA_SIZE 7
#define PARSER_TARGET_BUFFER modelData

uniform isamplerBuffer modelData;

struct ModelData {
    int worldViewIdx;
    int flags;
    vec3 position;
    int height;
    float fade; // Used by Dynamic Models
};

BEGIN_BUFFER_PARSER(readModelData, ModelData, true)
    READ_INT(worldViewIdx)
    READ_INT(flags)
    READ_VEC3(position)
    READ_INT(height)
    READ_FLOAT(fade)
END_BUFFER_PARSER()

ModelData getModelData(int modelIdx) {
    return readModelData((modelIdx - 1) * MODEL_DATA_SIZE);
}

bool isModelStatic(in ModelData modelData) {
    return (modelData.flags & 1) == 1;
}

bool isModelDynamic(in ModelData modelData) {
    return (modelData.flags & 1) == 0;
}

#undef PARSER_TARGET_BUFFER