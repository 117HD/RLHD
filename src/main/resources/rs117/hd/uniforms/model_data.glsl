#pragma once

#include <utils/texture_buffer_reader.glsl>

#define MODEL_DATA_SIZE 4
#define PARSER_TARGET_BUFFER modelData

uniform isamplerBuffer modelData;

struct ModelData {
    ivec3 position;
    int height;
};


BEGIN_BUFFER_PARSER(readModelData, ModelData)
    READ_IVEC3(position)
    READ_INT(height)
END_BUFFER_PARSER()

ModelData getModelData(int modelIdx) {
    return readModelData((modelIdx - 1) * MODEL_DATA_SIZE);
}

#undef PARSER_TARGET_BUFFER