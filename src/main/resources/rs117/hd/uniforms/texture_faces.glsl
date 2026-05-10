#pragma once

#include <utils/texture_buffer_reader.glsl>

uniform isamplerBuffer textureFaces;

struct FaceData {
    ivec3 AlphaBiasHsl;
    ivec3 MaterialData;
    ivec3 TerrainData;
};

BEGIN_BUFFER_PARSER(getFaceData,FaceData)
    READ_IVEC3(textureFaces, AlphaBiasHsl)
    READ_IVEC3(textureFaces, MaterialData)
    READ_IVEC3(textureFaces, TerrainData)
END_BUFFER_PARSER()