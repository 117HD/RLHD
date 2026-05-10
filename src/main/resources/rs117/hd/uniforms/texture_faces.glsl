#pragma once

#include <utils/texture_buffer_reader.glsl>

uniform isamplerBuffer textureFaces;

struct FaceData {
    ivec3 AlphaBiasHsl;
    ivec3 MaterialData;
    ivec3 TerrainData;
};

FaceData getFaceData(int faceOffset) {
    TexBufferReader reader = buildTexBufferReader(faceOffset);

    FaceData faceData;
    faceData.AlphaBiasHsl = readIVec3(textureFaces, reader);
    faceData.MaterialData = readIVec3(textureFaces, reader);
    faceData.TerrainData = readIVec3(textureFaces, reader);
    return faceData;
}