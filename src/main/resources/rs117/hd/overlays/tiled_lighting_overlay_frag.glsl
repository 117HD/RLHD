#version 330

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

uniform isampler2DArray tiledLightingArray;

#include <utils/constants.glsl>

in vec2 fUv;

out vec4 FragColor;

//#define DEBUG_LIGHT_COUNT_HEATMAP
//#define DEBUG_LIGHT_RADIUS_PADDING

void main() {
    vec2 uv = fUv;
    uv.y = 1 - uv.y;

    vec4 c = vec4(0);

#ifdef DEBUG_LIGHT_COUNT_HEATMAP

    ivec2 tileXY = ivec2(floor(uv * tiledLightingResolution));

    int tiledLightCount = 0;
    for (int idx = 0; idx < MAX_LIGHTS_PER_TILE; idx++) {
        int lightIdx = texelFetch(tiledLightingArray, ivec3(tileXY, idx), 0).r;
        if (lightIdx <= 0)
            break;

        lightIdx--;
        tiledLightCount++;
    }

    if (tiledLightCount > 0) {
        float level = (tiledLightCount / float(MAX_LIGHTS_PER_TILE)) * 3.14159265 / 2.0;
        c = vec4(sin(level), sin(level * 2), cos(level), 0.3);
    }

#else
#ifdef DEBUG_LIGHT_RADIUS_PADDING

    vec2 texelCenter = (floor(fUv * tiledLightingResolution) + .5) / tiledLightingResolution;

    // Draw texel centers
    vec2 viewportSize = viewport.zw;
    if (all(equal(floor(fUv * viewportSize), floor(texelCenter * viewportSize)))) {
        FragColor = vec4(1.0);
        return;
    }

    const float eps = 1e-10;
    vec2 ndcUv = (texelCenter * 2 - 1) * vec2(1, -1);
    vec4 farPos = invProjectionMatrix * vec4(ndcUv, eps, 1);
    vec3 viewDirCenter = normalize(farPos.xyz / farPos.w);

    ndcUv = (fUv * 2 - 1) * vec2(1, -1);
    farPos = invProjectionMatrix * vec4(ndcUv, eps, 1);
    vec3 viewDir = normalize(farPos.xyz / farPos.w);

    for (uint lightIdx = 0u; lightIdx < uint(MAX_LIGHT_COUNT); lightIdx++) {
        vec3 lightWorldPos = PointLightArray[lightIdx].position.xyz;
        float lightRadiusSq = PointLightArray[lightIdx].position.w;

        vec3 cameraToLight = lightWorldPos - cameraPos;
        // Check if the camera is outside of the light's radius
        if (dot(cameraToLight, cameraToLight) > lightRadiusSq) {
            float t = dot(cameraToLight, viewDirCenter);
            if (t < 0)
                continue; // Closest point is behind the camera

            const int tileSize = 16;
            float pad = tileSize * (512.f / cameraZoom) * 15;

            {
                // Finer UVs
                float t = dot(cameraToLight, viewDir);
                vec3 lightToClosestPoint = cameraToLight - t * viewDir;
                float dist = length(lightToClosestPoint);
                if (dist * dist < lightRadiusSq) {
                    c.b = 1;
                } else {
                    dist = max(0, dist - pad);
                    if (dist * dist < lightRadiusSq) {
                        c.g = 1;
                    }
                }
            }

            vec3 lightToClosestPoint = cameraToLight - t * viewDirCenter;
            float dist = length(lightToClosestPoint);
            dist = max(0, dist - pad);
            if (dist * dist > lightRadiusSq)
                continue; // View ray doesn't intersect with the light's sphere
        }

        c.r = 1;
    }

    if (length(c) > 0)
        c.a = 0.3;
#else
    ivec2 tileXY = ivec2(floor(uv * tiledLightingResolution));

    int idx = 0;
    for (; idx < MAX_LIGHTS_PER_TILE; idx++) {
        int lightIdx = texelFetch(tiledLightingArray, ivec3(tileXY, idx), 0).r;
        if (lightIdx <= 0)
            break;
    }

    if (idx > 0) {
        float level = (idx / float(MAX_LIGHTS_PER_TILE)) * 3.14159265 / 2.0;
        c = vec4(sin(level), sin(level * 2), cos(level), 0.3);
    }

#endif
#endif

    FragColor = c;
}
