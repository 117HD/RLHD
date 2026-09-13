#pragma once

#include <utils/texture_buffer_reader.glsl>

#define PARSER_TARGET_BUFFER textureFaces

uniform isamplerBuffer textureFaces;

struct StaticFaceData {
    // STATIC_FACE_FORMAT
    ivec3 AlphaBiasHsl;
    ivec3 MaterialData;
    ivec3 TerrainData;
};

struct ModelFaceData {
    // MODEL_FACE_FORMAT
    ivec3 AlphaBiasHsl;
    int MaterialData;
};

bool isFaceWindingReversed(int packedFaceData) {
    return (packedFaceData & 0x80000000) != 0;
}

bool isModelFace(int packedFaceData) {
    return (packedFaceData & 0x40000000) != 0;
}

int getFaceOffset(int packedFaceData) {
    return packedFaceData & 0x3FFFFFFF;
}

// STATIC_FACE_FORMAT
BEGIN_BUFFER_PARSER(getStaticFaceData, StaticFaceData, false)
    READ_IVEC3(AlphaBiasHsl)
    READ_IVEC3(MaterialData)
    READ_IVEC3(TerrainData)
END_BUFFER_PARSER()

// MODEL_FACE_FORMAT
BEGIN_BUFFER_PARSER(getModelFaceData, ModelFaceData, false)
    READ_IVEC3(AlphaBiasHsl)
    READ_INT(MaterialData)
END_BUFFER_PARSER()

#undef PARSER_TARGET_BUFFER
