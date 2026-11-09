#version 330

#include TILED_LIGHTING_DEPTH_CULLING
#include TILED_LIGHTING_MSAA
#include TILED_LIGHTING_LAYER
#include TILED_IMAGE_STORE

#define USE_LIGHTS_MASK !TILED_IMAGE_STORE

#if TILED_IMAGE_STORE
    #extension GL_EXT_shader_image_load_store : enable
    #extension GL_ARB_shader_image_load_store : enable
    layout(rgba16ui) coherent uniform uimage2DArray tiledLightingImage;
#else
    uniform usampler2DArray tiledLightingArray;
    out uvec4 TiledData;
#endif

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

#include <utils/constants.glsl>

#if TILED_LIGHTING_DEPTH_CULLING
    #if TILED_LIGHTING_MSAA > 1
        uniform sampler2DMS sceneDepthTexture;
    #else
        uniform sampler2D sceneDepthTexture;
    #endif
    uniform sampler2D alphaFirstLastDepth;
#endif

in vec2 fUv;

#if TILED_IMAGE_STORE
    #define SORTING_BIN_SIZE TILED_LIGHTING_MAX_TILE_LIGHT_COUNT
#else
    #define SORTING_BIN_SIZE 8
#endif

#include <utils/tiled_lighting.glsl>

void main() {
    ivec2 pixelCoord = ivec2(fUv * uboGlobal.tiledLightingResolution);

#if USE_LIGHTS_MASK
    int LightMaskSize = int(ceil(uboGlobal.pointLightsCount / 32.0));
    uint LightsMask[32]; // 32 Words = 1024 Lights
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
    vec2 screenUV = fUv * uboGlobal.sceneResolution;
    vec2 tileOrigin = floor(screenUV / tileSize) * tileSize;

    vec2 tl = tileOrigin + vec2(0.0, tileSize.y); // top-left
    vec2 tr = tileOrigin + tileSize;              // top-right
    vec2 bl = tileOrigin;                         // bottom-left
    vec2 br = tileOrigin + vec2(tileSize.x, 0.0); // bottom-right

#if TILED_LIGHTING_DEPTH_CULLING
    TileDepthInfo tile = computeTileDepthInfo(tileOrigin);

    // Empty tile (sky only): every light would be depth-culled anyway, so skip the light loop.
    if (tile.isEmpty) {
    #if TILED_IMAGE_STORE
        for (int layer = 0; layer < TILED_LIGHTING_LAYER_COUNT; layer++)
            imageStore(tiledLightingImage, ivec3(pixelCoord, layer), uvec4(0u));
        discard;
    #else
        TiledData = uvec4(0u);
        return;
    #endif
    }
#endif

    vec2 ndcTL = (tl / uboGlobal.sceneResolution) * 2.0 - 1.0;
    vec2 ndcTR = (tr / uboGlobal.sceneResolution) * 2.0 - 1.0;
    vec2 ndcBL = (bl / uboGlobal.sceneResolution) * 2.0 - 1.0;
    vec2 ndcBR = (br / uboGlobal.sceneResolution) * 2.0 - 1.0;

    const float eps = 1e-10;

    vec4 pTL = uboGlobal.invProjectionMatrix * vec4(ndcTL, eps, 1.0);
    vec4 pTR = uboGlobal.invProjectionMatrix * vec4(ndcTR, eps, 1.0);
    vec4 pBL = uboGlobal.invProjectionMatrix * vec4(ndcBL, eps, 1.0);
    vec4 pBR = uboGlobal.invProjectionMatrix * vec4(ndcBR, eps, 1.0);

    vec3 rTL = normalize((uboGlobal.viewMatrix * vec4((pTL.xyz / pTL.w) - uboGlobal.cameraPos, 1.0)).xyz);
    vec3 rTR = normalize((uboGlobal.viewMatrix * vec4((pTR.xyz / pTR.w) - uboGlobal.cameraPos, 1.0)).xyz);
    vec3 rBL = normalize((uboGlobal.viewMatrix * vec4((pBL.xyz / pBL.w) - uboGlobal.cameraPos, 1.0)).xyz);
    vec3 rBR = normalize((uboGlobal.viewMatrix * vec4((pBR.xyz / pBR.w) - uboGlobal.cameraPos, 1.0)).xyz);

    vec3 tileCenterVec = normalize(rTL + rTR + rBL + rBR);
    float tileCos = min(min(dot(tileCenterVec, rTL), dot(tileCenterVec, rTR)), min(dot(tileCenterVec, rBL), dot(tileCenterVec, rBR)));
    float tileSin = sqrt(max(0.0, 1.0 - tileCos * tileCos));

    mat4 viewToClip;
    vec2 tileNDCMin;
    vec2 tileNDCMax;
    if (uboGlobal.orthographicProjection) {
        viewToClip = uboGlobal.projectionMatrix * inverse(uboGlobal.viewMatrix);
        tileNDCMin = min(min(ndcTL, ndcTR), min(ndcBL, ndcBR));
        tileNDCMax = max(max(ndcTL, ndcTR), max(ndcBL, ndcBR));
    }

    SortedLight sortingBin[SORTING_BIN_SIZE];
    int sortingBinSize = 0;

    for (int lightIdx = 0; lightIdx < uboGlobal.pointLightsCount; lightIdx++) {
        vec4 lightData = uboLightsCulling.PointLightPositionsArray[lightIdx];
        vec3 lightViewPos = lightData.xyz;

        float lightDepth = abs(lightViewPos.z);
        float lightRadiusSqr = lightData.w;
        float lightRadius = sqrt(lightRadiusSqr);

#if TILED_LIGHTING_DEPTH_CULLING
        if (lightDepth + lightRadius < tile.minDepth ||
            lightDepth - lightRadius > tile.maxDepth)
            continue;
#endif

        float lightDistSqr = dot(lightViewPos, lightViewPos);

#if TILED_LIGHTING_DEPTH_CULLING
        // Signed distance from the light to the tile plane (positive = camera-facing side).
        float planeS = 0.0;
        if (tile.planeValid) {
            planeS = dot(tile.planeN, lightViewPos) - tile.planeD;
            // Slab cull: every surface point in the tile is within planeMaxDev of the plane, so a
            // sphere that doesn't reach the plane can't touch any of them. Independent of N.L.
            if (abs(planeS) > lightRadius + tile.planeMaxDev)
                continue;
        }
#endif

        float lightDist = sqrt(lightDistSqr);

#if TILED_LIGHTING_DEPTH_CULLING
        // Back-facing cull: light behind the plane means N.L < 0 across the whole tile.
        // Slack covers deviation from the plane plus normal tilt (across the tile and from
        // normal maps), scaled by how far the light can be from the tile.
        if (tile.planeValid && planeS < -tile.planeMaxDev) {
            float r = min(lightDist, lightRadius + tile.planeExtent);
            float slack = PLANE_CULL_TILT_SLACK + 2.0 * tile.planeMaxDev / tile.planeExtent;
            if (planeS < -(tile.planeMaxDev + r * slack))
                continue;
        }
#endif

        vec3 lightCenterVec = (lightDist > 0.0) ? lightViewPos / lightDist : vec3(0.0);

        float lightSinSqr = clamp(lightRadiusSqr / max(lightDistSqr, 1e-6), 0.0, 1.0);
        // Guard the sqrt: lightSinSqr reaches 1 as the camera approaches the light's radius, and
        // sqrt of a negative yields NaN, which then propagates through sumCos below.
        float lightCos = sqrt(max(0.0, 0.999 - lightSinSqr));
        float lightTileCos = dot(lightCenterVec, tileCenterVec);

        bool lightAffectsTile;
        float combinedScore;
        const float PROXIMITY_WEIGHT = 0.75;
        float distanceScore = clamp(1.0 - lightDist / (lightRadius + 1e-6), 0.0, 1.0);

        // With a valid plane, rank by distance to the tile's surface instead of to the camera.
        // Subtracting roughly the tile's half-diagonal stops lights that only touch the tile's
        // edge from all collapsing to a score of 0 (and tying). Every light in a tile uses the
        // same method, since planeValid is per tile, so scores stay comparable.
        bool  useSurfaceScore = false;
        float surfaceScore = 0.0;
#if TILED_LIGHTING_DEPTH_CULLING
        if (tile.planeValid) {
            float surfDist = max(length(lightViewPos - tile.planeCenter) - 0.71 * tile.planeExtent, 0.0);
            surfaceScore = clamp(1.0 - surfDist / (lightRadius + 1e-6), 0.0, 1.0);
            useSurfaceScore = true;
        }
#endif

        if (uboGlobal.orthographicProjection) {
            vec4 lightClip = viewToClip * vec4(lightViewPos, 1.0);
            vec2 lightNDC = lightClip.xy / max(abs(lightClip.w), 1e-5);

            vec4 radiusClip = viewToClip * vec4(lightViewPos + vec3(lightRadius, 0.0, 0.0), 1.0);
            vec2 radiusNDC = radiusClip.xy / max(abs(radiusClip.w), 1e-5);
            float lightNDCRadius = length(radiusNDC - lightNDC);

            vec2 closest = clamp(lightNDC, tileNDCMin, tileNDCMax);
            lightAffectsTile = length(lightNDC - closest) <= lightNDCRadius;
            combinedScore = useSurfaceScore ? surfaceScore : distanceScore;
        } else {
            // Once lightSinSqr passes 0.999 the camera is effectively inside the light's sphere of
            // influence, so the light can reach any tile. A ratio of 1 or more clamps to exactly 1.
            //
            // A PULSE light's radius sweeps +/-10% every cycle: at a camera distance inside that
            // sweep, sumCos was NaN, every comparison against NaN is false, and
            // 'lightTileCos >= sumCos' rejected the light from EVERY tile for a frame.
            float sumCos = (lightSinSqr > 0.999) ? -1.0 : (tileCos * lightCos - tileSin * sqrt(lightSinSqr));
            lightAffectsTile = lightTileCos >= sumCos;
            combinedScore = useSurfaceScore
                ? surfaceScore
                : (lightTileCos * PROXIMITY_WEIGHT) + distanceScore * (1.0 - PROXIMITY_WEIGHT);
        }

        if (!lightAffectsTile)
            continue;

        #if USE_LIGHTS_MASK
            uint word = uint(lightIdx) >> 5u;
            uint mask = 1u << (uint(lightIdx) & 31u);
            if ((LightsMask[word] & mask) != 0u)
                continue;
        #endif

        // Bin full and this light scores no better than the worst entry: nothing to do.
        if (sortingBinSize == SORTING_BIN_SIZE &&
            combinedScore <= sortingBin[SORTING_BIN_SIZE - 1].score)
            continue;

        // Find the insertion point (ties go after existing entries, as before).
        int idx = 0;
        while (idx < sortingBinSize && combinedScore <= sortingBin[idx].score)
            idx++;

        // Shift down, dropping the last entry when the bin is full (never writes out of bounds).
        for (int j = min(sortingBinSize, SORTING_BIN_SIZE - 1); j > idx; j--)
            sortingBin[j] = sortingBin[j - 1];

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
