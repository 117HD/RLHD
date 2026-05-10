#pragma once

#include <utils/texture_buffer_reader.glsl>

#define MODEL_DATA_SIZE 5
#define PARSER_TARGET_BUFFER modelData

uniform isamplerBuffer modelData;

struct ModelData {
    vec3 position;
    int height;
    float fade; // Used by Dynamic Models
};


BEGIN_BUFFER_PARSER(readModelData, ModelData)
    READ_VEC3(position)
    READ_INT(height)
    READ_FLOAT(fade)
END_BUFFER_PARSER()

ModelData getModelData(int modelIdx) {
    return readModelData((modelIdx - 1) * MODEL_DATA_SIZE);
}

#undef PARSER_TARGET_BUFFER