#pragma once

#include <utils/texture_buffer_reader.glsl>

uniform isamplerBuffer textureFaces;

struct FaceData {
    ivec3 AlphaBiasHsl;
    ivec3 MaterialData;
    ivec3 TerrainData;
};

#define PARSER_TARGET_BUFFER textureFaces

BEGIN_BUFFER_PARSER(getFaceData,FaceData)
    READ_IVEC3(AlphaBiasHsl)
    READ_IVEC3(MaterialData)
    READ_IVEC3(TerrainData)
END_BUFFER_PARSER()