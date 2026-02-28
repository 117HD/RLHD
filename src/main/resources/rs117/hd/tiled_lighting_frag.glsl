#version 330

#include TILED_LIGHTING_LAYER
#include TILED_IMAGE_STORE
#define TILE_MIN_MAX 1

#if TILED_IMAGE_STORE
    #extension GL_EXT_shader_image_load_store : enable
    #extension GL_ARB_shader_image_load_store : enable
    layout(rgba16ui) coherent uniform uimage2DArray tiledLightingImage;
#else
    uniform usampler2DArray tiledLightingArray;
    out uvec4 TiledData;
#endif

#include <utils/constants.glsl>
#include <utils/misc.glsl>

uniform sampler2D sceneOpaqueDepth;
uniform sampler2D sceneAlphaDepth;

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

in vec2 fUv;

#if TILED_IMAGE_STORE
    #define SORTING_BIN_SIZE TILED_LIGHTING_MAX_TILE_LIGHT_COUNT
#else
    #define SORTING_BIN_SIZE 8
#endif

struct SortedLight {
    int lightIdx;
    float score;
};

#define USE_LIGHTS_MASK !TILED_IMAGE_STORE

uint packLightIndices(in SortedLight bin[SORTING_BIN_SIZE], in int binSize, inout int binIdx) {
    if (binIdx >= binSize) return 0u;

    int idx0 = bin[binIdx].lightIdx;
    if (idx0 < 0) return 0u;

    idx0 += 1;
    binIdx += 1;

    if (binIdx < binSize) {
        int idx1 = bin[binIdx].lightIdx;
        if (idx1 >= 0) {
            idx1 += 1;
            // Try dual-pack: idx0 = 7 bits, idx1 = 8 bits
            if (idx0 <= 127 && idx1 <= 255) {
                binIdx += 1;
                return 0x8000u | (uint(idx1 & 0xFF) << 7) | uint(idx0 & 0x7F); // MSB = 1
            } else if (idx1 <= 127 && idx0 <= 255) {
                binIdx += 1;
                return 0x8000u | (uint(idx0 & 0xFF) << 7) | uint(idx1 & 0x7F); // MSB = 1
            }
        }
    }

    // Fallback: single 15-bit index
    return (idx0 <= 32767) ? uint(idx0 & 0x7FFF) : 0u;
}

#define SAMPLE_DEPTH(FRAC_X, FRAC_Y)                                                                                        \
    pix = ivec2(int(mix(float(minPix.x), float(maxPix.x), FRAC_X)), int(mix(float(minPix.y), float(maxPix.y), FRAC_Y)));    \
    depth = texelFetch(sceneOpaqueDepth, pix, 0).r;                                                                         \
    if (depth > 0.0) {                                                                                                      \
       minDepth = min(minDepth, depth);                                                                                     \
       maxDepth = max(maxDepth, depth);                                                                                     \
    }                                                                                                                       \
    depth = texelFetch(sceneAlphaDepth, pix, 0).r;                                                                          \
    if(depth > 0.0)                                                                                                         \
       maxDepth = max(maxDepth, depth);                                                                                     \


bool calculateTileMinMax(vec2 bl, vec2 tr, out float tileMin, out float tileMax) {
#if TILE_MIN_MAX
    ivec2 minPix = clamp(ivec2(floor(bl)), ivec2(0), ivec2(sceneResolution) - 1);
    ivec2 maxPix = clamp(ivec2(ceil(tr)), ivec2(0), ivec2(sceneResolution));

    // Instead of sampling 16x16x2, it's accurate enough just to sample the corners and mid points
    float minDepth = 1e20;
    float maxDepth = -1e20;

    ivec2 pix;
    float depth;
    SAMPLE_DEPTH(0.0, 0.0)
    SAMPLE_DEPTH(0.5, 0.0)
    SAMPLE_DEPTH(1.0, 0.0)

    SAMPLE_DEPTH(0.0, 0.5)
    SAMPLE_DEPTH(0.5, 0.5)
    SAMPLE_DEPTH(1.0, 0.5)

    SAMPLE_DEPTH(0.0, 1.0)
    SAMPLE_DEPTH(0.5, 1.0)
    SAMPLE_DEPTH(1.0, 1.0)

    if(minDepth > maxDepth)
        return false;

    tileMin = depth01ToViewZ(minDepth, projectionMatrix);
    tileMax = depth01ToViewZ(maxDepth, projectionMatrix);
#endif
    return true;
}

void main() {
    ivec2 pixelCoord = ivec2(fUv * tiledLightingResolution);

#if USE_LIGHTS_MASK
    int LightMaskSize = int(ceil(pointLightsCount / 32.0));
    uint LightsMask[(TILED_LIGHTING_LAYER + 1) * 4]; // 32 Words = 1024 Lights
    for (int i = 0; i < LightMaskSize; i++)
        LightsMask[i] = 0u;

    #if TILED_LIGHTING_LAYER > 0
        int LayerCount = TILED_LIGHTING_LAYER - 1;
        for (int l = LayerCount; l >= 0; l--) {
            uvec4 layerData = texelFetch(tiledLightingArray, ivec3(pixelCoord, l), 0);
            for (int c = 3; c >= 0; c--) {
                ivec2 unpacked = decodePackedLight(layerData[c]);
                for (int i = 0; i < (isDualPacked(layerData[c]) ? 2 : 1); i++) {
                    int encodedLightIdx = unpacked[i];

                    if (encodedLightIdx < 0) {
                        TiledData = uvec4(0.0);
                        return; // No more lights are overlapping with cell since the previous layer didn't encode
                    }

                    uint word = uint(encodedLightIdx) >> 5u;
                    uint mask = 1u << (uint(encodedLightIdx) & 31u);
                    LightsMask[word] |= mask;
                }
            }
        }
    #endif
#endif

    const vec2 tileSize = vec2(TILED_LIGHTING_TILE_SIZE);
    vec2 screenUV = fUv * sceneResolution;
    vec2 tileOrigin = floor(screenUV / tileSize) * tileSize;

    vec2 tl = tileOrigin + vec2(0.0, tileSize.y); // top-left
    vec2 tr = tileOrigin + tileSize;              // top-right
    vec2 bl = tileOrigin;                         // bottom-left
    vec2 br = tileOrigin + vec2(tileSize.x, 0.0); // bottom-right

#if TILE_MIN_MAX
    float tileMin = 0.0f;
    float tileMax = 1.0f;
    if(!calculateTileMinMax(bl, tr, tileMin, tileMax)) {
    #if TILED_IMAGE_STORE
        for (int layer = 0; layer < TILED_LIGHTING_LAYER_COUNT; layer++)
            imageStore(tiledLightingImage, ivec3(pixelCoord, layer), uvec4(0.0));
    #else
        TiledData = uvec4(0.0);
    #endif
        return;
    }
#endif

    vec2 ndcTL = (tl / sceneResolution) * 2.0 - 1.0;
    vec2 ndcTR = (tr / sceneResolution) * 2.0 - 1.0;
    vec2 ndcBL = (bl / sceneResolution) * 2.0 - 1.0;
    vec2 ndcBR = (br / sceneResolution) * 2.0 - 1.0;

    const float eps = 1e-10;

    vec4 pTL = invProjectionMatrix * vec4(ndcTL, eps, 1.0);
    vec4 pTR = invProjectionMatrix * vec4(ndcTR, eps, 1.0);
    vec4 pBL = invProjectionMatrix * vec4(ndcBL, eps, 1.0);
    vec4 pBR = invProjectionMatrix * vec4(ndcBR, eps, 1.0);

    vec3 rTL = normalize((viewMatrix * vec4((pTL.xyz / pTL.w) - cameraPos, 1.0)).xyz);
    vec3 rTR = normalize((viewMatrix * vec4((pTR.xyz / pTR.w) - cameraPos, 1.0)).xyz);
    vec3 rBL = normalize((viewMatrix * vec4((pBL.xyz / pBL.w) - cameraPos, 1.0)).xyz);
    vec3 rBR = normalize((viewMatrix * vec4((pBR.xyz / pBR.w) - cameraPos, 1.0)).xyz);

    vec3 tileCenterVec = normalize(rTL + rTR + rBL + rBR);
    float tileCos = min(min(dot(tileCenterVec, rTL), dot(tileCenterVec, rTR)), min(dot(tileCenterVec, rBL), dot(tileCenterVec, rBR)));
    float tileSin = sqrt(max(0.0, 1.0 - tileCos * tileCos));

    SortedLight sortingBin[SORTING_BIN_SIZE];
    int sortingBinSize = 0;

    for (int lightIdx = 0; lightIdx < pointLightsCount; lightIdx++) {
        vec4 lightData = PointLightPositionsArray[lightIdx];
        vec3 lightViewPos = lightData.xyz;
        float lightRadiusSqr = lightData.w;

        #if TILE_MIN_MAX
            float dz = max(lightViewPos.z - tileMin, tileMax - lightViewPos.z);
            if (dz > 0.0 && dz * dz > lightRadiusSqr)
                continue;
        #endif

        float lightTileDot = dot(lightViewPos, tileCenterVec);
        float lightDistSqr = dot(lightViewPos, lightViewPos);
        if (lightTileDot * lightTileDot < lightDistSqr * tileCos * tileCos - lightRadiusSqr)
            continue;

        #if USE_LIGHTS_MASK
            uint word = uint(lightIdx) >> 5u;
            uint mask = 1u << (uint(lightIdx) & 31u);
            if ((LightsMask[word] & mask) != 0u)
                continue;
        #endif

        const float PROXIMITY_WEIGHT = 0.75;
        float distanceScore = clamp(1.0 - lightDistSqr / (lightRadiusSqr + 1e-6), 0.0, 1.0);
        float angularScore = lightTileDot * lightTileDot / (lightDistSqr + 1e-6);
        float combinedScore = (angularScore * PROXIMITY_WEIGHT) + distanceScore * (1.0 - PROXIMITY_WEIGHT);

        int idx = 0;
        for (; idx < sortingBinSize; idx++) {
            if (combinedScore > sortingBin[idx].score) {
                for (int j = sortingBinSize; j > idx; j--)
                    sortingBin[j] = sortingBin[j - 1];
                break;
            }
        }

        sortingBin[idx].score = combinedScore;
        sortingBin[idx].lightIdx = lightIdx;
        if (sortingBinSize < SORTING_BIN_SIZE)
            sortingBinSize++;
    }

#if TILED_IMAGE_STORE
    uvec4 outputTileData = uvec4(0);
    for (int layer = 0, binIdx = 0; layer < TILED_LIGHTING_LAYER_COUNT; layer++) {
        for (int c = 0; c < 4; c++)
            outputTileData[c] = packLightIndices(sortingBin, sortingBinSize, binIdx);
        imageStore(tiledLightingImage, ivec3(pixelCoord, layer), outputTileData);
    }
    discard;
#else
    uvec4 outputTileData = uvec4(0);
    for (int c = 0, binIdx = 0; c < 4 && binIdx < sortingBinSize; c++)
        outputTileData[c] = packLightIndices(sortingBin, sortingBinSize, binIdx);
    TiledData = outputTileData;
#endif
}
