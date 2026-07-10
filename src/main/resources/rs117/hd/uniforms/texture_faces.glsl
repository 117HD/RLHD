#pragma once

#include <utils/texture_buffer_reader.glsl>

#define PARSER_TARGET_BUFFER textureFaces

uniform isamplerBuffer textureFaces;

struct StaticFaceData {
    ivec3 AlphaBiasHsl;
    ivec3 MaterialData;
    ivec3 TerrainData;
};

struct ModelFaceData {
    ivec3 AlphaBiasHsl;
    int MaterialData;
};

bool isModelFace(int packedFaceData) {
    return (packedFaceData & 1) == 1;
}

int getFaceOffset(int packedFaceData) {
    return packedFaceData >> 1;
}

BEGIN_BUFFER_PARSER(getStaticFaceData,StaticFaceData, false)
    READ_IVEC3(AlphaBiasHsl)
    READ_IVEC3(MaterialData)
    READ_IVEC3(TerrainData)
END_BUFFER_PARSER()

BEGIN_BUFFER_PARSER(getModelFaceData,ModelFaceData, false)
    READ_IVEC3(AlphaBiasHsl)
    READ_INT(MaterialData)
END_BUFFER_PARSER()

#undef PARSER_TARGET_BUFFER