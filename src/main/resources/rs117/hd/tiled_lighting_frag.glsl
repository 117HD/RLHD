#version 330

#include TILED_LIGHTING_DEPTH_CULLING
#include TILED_LIGHTING_MSAA
#include TILED_LIGHTING_LAYER
#include TILED_IMAGE_STORE

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
    uniform bool hasAlphaDepth;
#endif

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

// Slack (in units of normal tilt, ~radians) so the back-facing plane cull stays conservative
// against normal maps / smooth shading that bend the shading normal away from the geometric
// one. 0.25 is roughly 14 degrees. Tune.
const float PLANE_CULL_TILT_SLACK = 0.25;

// Reconstructs the view-space position of a depth sample.
// NOTE: must be in the same space as uboLightsCulling.PointLightPositionsArray.
vec3 reconstructViewPos(ivec2 pixel, float depth) {
    vec2 ndcXY = (vec2(pixel) + 0.5) / vec2(uboGlobal.sceneResolution) * 2.0 - 1.0;
    vec4 worldPos = uboGlobal.invProjectionMatrix * vec4(ndcXY, depth * 2.0 - 1.0, 1.0);
    worldPos /= worldPos.w;
    return (uboGlobal.viewMatrix * worldPos).xyz;
}

float reconstructViewDepth(ivec2 pixel, float depth) {
    return abs(reconstructViewPos(pixel, depth).z);
}

#if TILED_LIGHTING_DEPTH_CULLING
float fetchAnchorDepth(ivec2 p) {
    return texelFetch(sceneDepthTexture, p, 0).r; // sample 0 for MSAA
}
#endif

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
    ivec2 tilePixelMin = ivec2(tileOrigin);
    ivec2 tilePixelMax = min(tilePixelMin + ivec2(TILED_LIGHTING_TILE_SIZE), uboGlobal.sceneResolution);
    float tileMinDepth = 1e30;
    float tileMaxDepth = 0.0;

    // ---- Candidate tile plane, built from three anchor samples ----
    // If the tile's surfaces are not actually planar, the residuals measured below grow and
    // planeMaxDev grows with them, so the plane culls stop firing (the safe failure mode).
    bool  planeValid    = false;
    vec3  planeN        = vec3(0.0);
    float planeD        = 0.0;
    float planeExtent   = 1.0;
    float planeMaxDev   = 0.0;
    float planeRayScale = 1.0;
    vec3  planeCenter   = vec3(0.0); // surface point at the middle of the tile (view space)
    bool  tileHasAlpha  = false;

    // Screen-space plane for RAW depth: for a planar surface, post-projection depth is affine in
    // screen xy, so per-sample deviation is two MADs and needs no position reconstruction.
    float anchorZ0 = 0.0;
    float dzdx = 0.0;
    float dzdy = 0.0;

    {
        ivec2 a0 = tilePixelMin;
        ivec2 a1 = ivec2(tilePixelMax.x - 1, tilePixelMin.y);
        ivec2 a2 = ivec2(tilePixelMin.x, tilePixelMax.y - 1);
        float z0 = fetchAnchorDepth(a0);
        float z1 = fetchAnchorDepth(a1);
        float z2 = fetchAnchorDepth(a2);

        if (z0 > 0.0 && z1 > 0.0 && z2 > 0.0) {
            vec3 p0 = reconstructViewPos(a0, z0);
            vec3 p1 = reconstructViewPos(a1, z1);
            vec3 p2 = reconstructViewPos(a2, z2);
            vec3 e1 = p1 - p0;
            vec3 e2 = p2 - p0;
            vec3 n = cross(e1, e2);
            float nLen = length(n);
            float l1 = length(e1);
            float l2 = length(e2);
            if (nLen > 1e-3 * l1 * l2) { // reject near-degenerate (extreme grazing / collinear)
                n /= nLen;
                if (dot(n, p0) > 0.0) n = -n; // face the camera (origin of view space)
                planeN = n;
                planeD = dot(n, p0);
                planeCenter = p0 + 0.5 * (e1 + e2);
                planeExtent = max(min(l1, l2), 1e-4);

                anchorZ0 = z0;
                dzdx = (z1 - z0) / max(float(a1.x - a0.x), 1.0);
                dzdy = (z2 - z0) / max(float(a2.y - a0.y), 1.0);

                // A view-z error is a distance error along the view ray, which is longer than
                // the z error by 1/cos(angle to the view axis). Perspective only.
                if (!uboGlobal.orthographicProjection) {
                    planeRayScale = max(max(length(p0) / max(abs(p0.z), 1e-4),
                                            length(p1) / max(abs(p1.z), 1e-4)),
                                        length(p2) / max(abs(p2.z), 1e-4));
                }
                planeValid = true;
            }
        }
    }

    // Raw-depth extremes and residual range vs. the screen-space plane. Reconstruction to view
    // space happens once after the loop rather than once per sample.
    float minRaw = 1e30;
    float maxRaw = 0.0;
    float minRes = 0.0;
    float maxRes = 0.0;
    float alphaMin = 1e30;
    float alphaMax = 0.0;

    for (int y = tilePixelMin.y; y < tilePixelMax.y; y++) {
        for (int x = tilePixelMin.x; x < tilePixelMax.x; x++) {
            ivec2 pixel = ivec2(x, y);
            float planeZ = anchorZ0 + dzdx * float(x - tilePixelMin.x) + dzdy * float(y - tilePixelMin.y);

            #if TILED_LIGHTING_MSAA > 1
                for (int sampleIdx = 0; sampleIdx < TILED_LIGHTING_MSAA; sampleIdx++) {
                    float depth = texelFetch(sceneDepthTexture, pixel, sampleIdx).r;
                    if (depth > 0.0) {
                        minRaw = min(minRaw, depth);
                        maxRaw = max(maxRaw, depth);
                        float res = depth - planeZ;
                        minRes = min(minRes, res);
                        maxRes = max(maxRes, res);
                    }
                }
            #else
                float depth = texelFetch(sceneDepthTexture, pixel, 0).r;
                if (depth > 0.0) {
                    minRaw = min(minRaw, depth);
                    maxRaw = max(maxRaw, depth);
                    float res = depth - planeZ;
                    minRes = min(minRes, res);
                    maxRes = max(maxRes, res);
                }
            #endif

            if (hasAlphaDepth) {
                vec2 alphaDepth = texelFetch(alphaFirstLastDepth, pixel, 0).rg;
                if (alphaDepth.x < 1e5) {
                    float depthScale = uboGlobal.drawDistance * TILE_SIZE;
                    alphaMin = min(alphaMin, alphaDepth.x * depthScale);
                    alphaMax = max(alphaMax, -alphaDepth.y * depthScale);
                    tileHasAlpha = true;
                }
            }
        }
    }

    if (maxRaw > 0.0) {
        // View z depends only on depth for a standard projection, so any pixel in the tile works.
        ivec2 centerPx = (tilePixelMin + tilePixelMax) / 2;
        float vLo = reconstructViewDepth(centerPx, minRaw);
        float vHi = reconstructViewDepth(centerPx, maxRaw);
        // min/max of both, so the depth convention (standard / reversed) doesn't matter
        tileMinDepth = min(vLo, vHi);
        tileMaxDepth = max(vLo, vHi);

        if (planeValid) {
            // Convert the raw-depth residual range into a view-space deviation. View z is most
            // sensitive to depth error at the far end of the range, so evaluating there bounds
            // every sample in the tile.
            float farRaw = (vLo > vHi) ? minRaw : maxRaw;
            float vFar = max(vLo, vHi);
            float rawA = farRaw - minRes; // where the plane would put the sample at the extremes
            float rawB = farRaw - maxRes;
            if (rawA > 0.0 && rawB > 0.0 && rawA <= 1.0 && rawB <= 1.0) {
                float dA = abs(reconstructViewDepth(centerPx, rawA) - vFar);
                float dB = abs(reconstructViewDepth(centerPx, rawB) - vFar);
                planeMaxDev = max(dA, dB) * planeRayScale;
            } else {
                planeValid = false; // residual pushes depth out of range: give up on the plane
            }
        }
    }

    tileMinDepth = min(tileMinDepth, alphaMin);
    tileMaxDepth = max(tileMaxDepth, alphaMax);

    // Transparent surfaces have their own normals and are lit from the same tile list, so the
    // single-plane assumption doesn't hold for them.
    planeValid = planeValid && !tileHasAlpha && (tileMinDepth <= tileMaxDepth);

    // Empty tile (sky only): every light would be depth-culled anyway, so skip the light loop.
    if (tileMinDepth > tileMaxDepth) {
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
        if (tileMinDepth > tileMaxDepth ||
            lightDepth + lightRadius < tileMinDepth ||
            lightDepth - lightRadius > tileMaxDepth)
            continue;
#endif

        float lightDistSqr = dot(lightViewPos, lightViewPos);

#if TILED_LIGHTING_DEPTH_CULLING
        // Signed distance from the light to the tile plane (positive = camera-facing side).
        float planeS = 0.0;
        if (planeValid) {
            planeS = dot(planeN, lightViewPos) - planeD;
            // Slab cull: every surface point in the tile is within planeMaxDev of the plane, so a
            // sphere that doesn't reach the plane can't touch any of them. Independent of N.L.
            if (abs(planeS) > lightRadius + planeMaxDev)
                continue;
        }
#endif

        float lightDist = sqrt(lightDistSqr);

#if TILED_LIGHTING_DEPTH_CULLING
        // Back-facing cull: light behind the plane means N.L < 0 across the whole tile.
        // Slack covers deviation from the plane plus normal tilt (across the tile and from
        // normal maps), scaled by how far the light can be from the tile.
        if (planeValid && planeS < -planeMaxDev) {
            float r = min(lightDist, lightRadius + planeExtent);
            float slack = PLANE_CULL_TILT_SLACK + 2.0 * planeMaxDev / planeExtent;
            if (planeS < -(planeMaxDev + r * slack))
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
        if (planeValid) {
            float surfDist = max(length(lightViewPos - planeCenter) - 0.71 * planeExtent, 0.0);
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
            // influence, so the light can reach any tile - the same conclusion the old
            // lightRadiusSqr > lightDistSqr test drew, widened to cover the whole range where the
            // angular math degenerates. Testing lightSinSqr subsumes that case, since a ratio of 1
            // or more clamps to exactly 1.
            //
            // This matters because a PULSE light's radius sweeps +/-10% every cycle: at a camera
            // distance inside that sweep, the light crossed this window for a single frame twice
            // per cycle. sumCos was NaN there, every comparison against NaN is false, so
            // 'lightTileCos >= sumCos' rejected the light from EVERY tile at once - the light
            // vanished entirely for a frame, on the pulse's period. (master used the inverted form
            // 'if (lightTileCos < sumCos) continue', which fails open on NaN and keeps the light,
            // which is why master never showed this.)
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
