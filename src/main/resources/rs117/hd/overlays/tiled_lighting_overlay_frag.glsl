#version 330

// Plane debug views. 0 = off (normal light-count heatmap)
//   1 = plane normal, world space  (stable while the camera rotates)
//   2 = plane status: green = valid, red = rejected, blue = has alpha, dark = empty/sky
//    = facing ratio dot(n, -viewRay) (dark = grazing)
#define DEBUG_PLANE_MODE 0

//#define DEBUG_LIGHT_RADIUS_PADDING

// The plane debug needs the same defines / depth inputs as the culling pass.
// The overlay program must be given TILED_LIGHTING_DEPTH_CULLING / TILED_LIGHTING_MSAA and have
// sceneDepthTexture and alphaFirstLastDepth bound, exactly like the culling program.
#include TILED_LIGHTING_DEPTH_CULLING
#include TILED_LIGHTING_MSAA

#if DEBUG_PLANE_MODE > 0 && TILED_LIGHTING_DEPTH_CULLING
    #define PLANE_DEBUG
#endif

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

uniform usampler2DArray tiledLightingArray;

#include <utils/constants.glsl>
#include <utils/misc.glsl>

#ifdef PLANE_DEBUG
    #if TILED_LIGHTING_MSAA > 1
        uniform sampler2DMS sceneDepthTexture;
    #else
        uniform sampler2D sceneDepthTexture;
    #endif
    uniform sampler2D alphaFirstLastDepth;

    // tiled_lighting.glsl sizes packLightIndices() with this; the overlay never calls it.
    #define SORTING_BIN_SIZE 8
    #include <utils/tiled_lighting.glsl>
#endif

in vec2 fUv;

out vec4 FragColor;

#ifdef PLANE_DEBUG

vec4 planeStatusColor(TileDepthInfo t) {
    if (t.isEmpty)     return vec4(0.0, 0.0, 0.0, 0.35);
    if (t.hasAlpha)    return vec4(0.1, 0.3, 1.0, 0.6);
    if (!t.planeValid) return vec4(1.0, 0.0, 0.0, 0.6);
    return vec4(0.0, 0.8, 0.2, 0.6);
}

vec4 planeDebugColor(TileDepthInfo t, vec2 scenePx, vec2 tileOrigin) {
    vec4 col = planeStatusColor(t);

#if DEBUG_PLANE_MODE != 2
    if (t.planeValid) {
    #if DEBUG_PLANE_MODE == 1
        // viewMatrix is used as a pure rotation here (same assumption as reconstructViewPos),
        // so the inverse is the transpose.
        vec3 nWorld = transpose(mat3(uboGlobal.viewMatrix)) * t.planeN;
        col = vec4(nWorld * 0.5 + 0.5, 0.9);

    #elif DEBUG_PLANE_MODE == 3
        vec3 viewRay = uboGlobal.orthographicProjection
            ? vec3(0.0, 0.0, -1.0)
            : normalize(t.planeCenter);
        float facing = clamp(dot(t.planeN, -viewRay), 0.0, 1.0);
        col = vec4(vec3(facing), 0.9);
    #endif
    }
#endif

    // Faint tile grid so flat colours stay readable tile by tile.
    if (any(lessThan(scenePx - tileOrigin, vec2(1.0))))
        col.rgb *= 0.6;

    return col;
}

#endif // PLANE_DEBUG

void main() {
#ifdef PLANE_DEBUG
    {
        const vec2 tileSize = vec2(TILED_LIGHTING_TILE_SIZE);
        vec2 scenePx = fUv * uboGlobal.sceneResolution;
        vec2 tileOrigin = floor(scenePx / tileSize) * tileSize;

        TileDepthInfo tile = computeTileDepthInfo(tileOrigin);
        FragColor = planeDebugColor(tile, scenePx, tileOrigin);
        return;
    }
#endif

    vec2 texelCenter = (floor(fUv * uboGlobal.tiledLightingResolution) + .5) / uboGlobal.tiledLightingResolution;

    const float eps = 1e-10;
    vec2 ndcUv = fUv * 2 - 1;
    vec4 farPos = uboGlobal.invProjectionMatrix * vec4(ndcUv, eps, 1);
    vec3 viewDir = normalize(farPos.xyz / farPos.w);

    vec4 c = vec4(0);

    ivec2 tileXY = ivec2(floor(fUv * uboGlobal.tiledLightingResolution));
    int tiledLightCount = 0;
    for (int tileLayer = 0; tileLayer < TILED_LIGHTING_LAYER_COUNT; tileLayer++) {
        uvec4 tileLayerData = texelFetch(tiledLightingArray, ivec3(tileXY, tileLayer), 0);
        for (int c = 0; c < 4; c++) {
            if (tileLayerData[c] <= 0u)
                break;

            ivec2 unpacked = decodePackedLight(tileLayerData[c]);
            if (unpacked[0] >= 0) tiledLightCount++;
            if (unpacked[1] >= 0) tiledLightCount++;
        }
    }

    if (tiledLightCount > 0) {
        if (tiledLightCount < TILED_LIGHTING_MAX_TILE_LIGHT_COUNT) {
            c = heatmap(tiledLightCount, TILED_LIGHTING_MAX_TILE_LIGHT_COUNT);
        } else {
            c = vec4(1, 0, 1, 0.6);
        }
    }

    #ifdef DEBUG_LIGHT_RADIUS_PADDING
        // Render the light as a red sphere
        // When the light passes the central intersection test, the tile is colored green
        // When the light passes the padded intersection test, the tile is colored blue
        // When both tests fail to include the light, the unincluded portion is colored white

        // Draw texel centers
        if (all(equal(floor(fUv * uboGlobal.sceneResolution), floor(texelCenter * uboGlobal.sceneResolution)))) {
            FragColor = vec4(1);
            return;
        }

        for (uint lightIdx = 0u; lightIdx < uint(uboGlobal.pointLightsCount); lightIdx++) {
            PointLight light = uboLights.PointLightArray[lightIdx];
            vec3 lightWorldPos = light.position.xyz;
            float lightRadiusSq = light.position.w;
            vec3 cameraToLight = lightWorldPos - uboGlobal.cameraPos;

            // Calculate the distance from the camera to the point closest to the light along the view ray
            float t = dot(cameraToLight, viewDir);
            if (t < 0) {
                // If the closest point lies behind the camera, the light can only contribute to the visible
                // scene if the camera happens to be within the light's radius
                if (dot(cameraToLight, cameraToLight) > lightRadiusSq)
                    continue;
                c.g = 1;
            } else {
                // High resolution UVs
                vec3 accurateLightToClosestPoint = cameraToLight - t * viewDir;
                float accurateDistSq = dot(accurateLightToClosestPoint, accurateLightToClosestPoint);
                if (accurateDistSq < lightRadiusSq)
                    c.r = 1;
            }
        }

        if (length(c) > 0 && c.a == 0)
            c.a = 1;
    #endif

    FragColor = c;
}