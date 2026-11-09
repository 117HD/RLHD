#pragma once

struct SortedLight {
    int lightIdx;
    float score;
};

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


// Slack (in units of normal tilt, ~radians) so the back-facing plane cull stays conservative
// against normal maps / smooth shading that bend the shading normal away from the geometric
// one. 0.5 is roughly 28 degrees
const float PLANE_CULL_TILT_SLACK = 0.5;

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

struct TileDepthInfo {
    float minDepth;     // view-space min depth (incl. alpha surfaces)
    float maxDepth;     // view-space max depth (incl. alpha surfaces)
    bool  isEmpty;      // sky only: minDepth > maxDepth
    bool  hasAlpha;     // tile contains transparent surfaces

    bool  planeValid;   // plane fit usable for culling
    vec3  planeN;       // view-space normal, facing the camera
    float planeD;       // dot(planeN, point on plane)
    vec3  planeCenter;  // surface point at tile middle (view space)
    float planeExtent;  // shortest anchor edge length (world units)
    float planeMaxDev;  // max view-space deviation of any sample from the plane
};

TileDepthInfo computeTileDepthInfo(ivec2 tilePixelMin, ivec2 tilePixelMax) {
    TileDepthInfo info;
    info.minDepth    = 1e30;
    info.maxDepth    = 0.0;
    info.isEmpty     = true;
    info.hasAlpha    = false;
    info.planeValid  = false;
    info.planeN      = vec3(0.0);
    info.planeD      = 0.0;
    info.planeCenter = vec3(0.0);
    info.planeExtent = 1.0;
    info.planeMaxDev = 0.0;

    float planeRayScale = 1.0;
    float anchorZ0 = 0.0;
    float dzdx = 0.0;
    float dzdy = 0.0;

    // ---- Candidate tile plane, built from three anchor samples ----
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
            if (nLen > 1e-3 * l1 * l2) {
                n /= nLen;
                if (dot(n, p0) > 0.0) n = -n;
                info.planeN      = n;
                info.planeD      = dot(n, p0);
                info.planeCenter = p0 + 0.5 * (e1 + e2);
                info.planeExtent = max(min(l1, l2), 1e-4);

                anchorZ0 = z0;
                dzdx = (z1 - z0) / max(float(a1.x - a0.x), 1.0);
                dzdy = (z2 - z0) / max(float(a2.y - a0.y), 1.0);

                if (!uboGlobal.orthographicProjection) {
                    planeRayScale = max(max(length(p0) / max(abs(p0.z), 1e-4),
                                            length(p1) / max(abs(p1.z), 1e-4)),
                                        length(p2) / max(abs(p2.z), 1e-4));
                }
                info.planeValid = true;
            }
        }
    }

    // ---- Raw-depth extremes and residual range vs. the screen-space plane ----
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

            vec2 alphaDepth = texelFetch(alphaFirstLastDepth, pixel, 0).rg;
            if (alphaDepth.x < 1e5) {
                float depthScale = uboGlobal.drawDistance * TILE_SIZE;
                alphaMin = min(alphaMin, alphaDepth.x * depthScale);
                alphaMax = max(alphaMax, -alphaDepth.y * depthScale);
                info.hasAlpha = true;
            }
        }
    }

    if (maxRaw > 0.0) {
        ivec2 centerPx = (tilePixelMin + tilePixelMax) / 2;
        float vLo = reconstructViewDepth(centerPx, minRaw);
        float vHi = reconstructViewDepth(centerPx, maxRaw);
        info.minDepth = min(vLo, vHi);
        info.maxDepth = max(vLo, vHi);

        if (info.planeValid) {
            float farRaw = (vLo > vHi) ? minRaw : maxRaw;
            float vFar = max(vLo, vHi);
            float rawA = farRaw - minRes;
            float rawB = farRaw - maxRes;
            if (rawA > 0.0 && rawB > 0.0 && rawA <= 1.0 && rawB <= 1.0) {
                float dA = abs(reconstructViewDepth(centerPx, rawA) - vFar);
                float dB = abs(reconstructViewDepth(centerPx, rawB) - vFar);
                info.planeMaxDev = max(dA, dB) * planeRayScale;
            } else {
                info.planeValid = false;
            }
        }
    }

    info.minDepth = min(info.minDepth, alphaMin);
    info.maxDepth = max(info.maxDepth, alphaMax);

    info.isEmpty = info.minDepth > info.maxDepth;

    // Transparent surfaces have their own normals; single-plane assumption doesn't hold.
    info.planeValid = info.planeValid && !info.hasAlpha && !info.isEmpty;

    return info;
}

TileDepthInfo computeTileDepthInfo(vec2 tileOrigin) {
    ivec2 tpMin = ivec2(tileOrigin);
    ivec2 tpMax = min(tpMin + ivec2(TILED_LIGHTING_TILE_SIZE), uboGlobal.sceneResolution);
    return computeTileDepthInfo(tpMin, tpMax);
}

#endif