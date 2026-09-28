#pragma once

#include <utils/texture_buffer_reader.glsl>

#define MODEL_DATA_SIZE 7
#define PARSER_TARGET_BUFFER modelData

#ifdef SHADER_STORAGE_BUFFERS
// Same tightly-packed scalar layout as the texture buffer below
layout(std430, binding = MODEL_DATA_SSBO_BINDING) buffer ModelDataBuffer {
    int data[];
} modelData;
#else
uniform isamplerBuffer modelData;
#endif

struct ModelData {
    // MODEL_DATA_FORMAT
    int worldViewIdx;
    int flags;
    vec3 position;
    int height;
    float fade; // Used by Dynamic Models
};

// MODEL_DATA_FORMAT
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
    return !isModelStatic(modelData);
}

#undef PARSER_TARGET_BUFFER
