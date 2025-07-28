#version 330

//#define DEBUG_LIGHT_RADIUS_PADDING

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

uniform isampler2DArray tiledLightingArray;

#include <utils/constants.glsl>

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec2 uv = fUv;
    uv.y = 1 - uv.y;

    vec2 texelCenter = (floor(fUv * tiledLightingResolution) + .5) / tiledLightingResolution;

    const float eps = 1e-10;
    vec2 ndcUv = (fUv * 2 - 1) * vec2(1, -1);
    vec4 farPos = invProjectionMatrix * vec4(ndcUv, eps, 1);
    vec3 viewDir = normalize(farPos.xyz / farPos.w);

    vec4 c = vec4(0);
    #ifdef DEBUG_LIGHT_RADIUS_PADDING
        // Render the light as a red sphere
        // When the light passes the central intersection test, the tile is colored green
        // When the light passes the padded intersection test, the tile is colored blue
        // When both tests fail to include the light, the unincluded portion is colored white

        // Draw texel centers
        if (all(equal(floor(fUv * sceneResolution), floor(texelCenter * sceneResolution)))) {
            FragColor = vec4(1);
            return;
        }

        vec2 ndcUvCenter = (texelCenter * 2 - 1) * vec2(1, -1);
        vec4 farPosCenter = invProjectionMatrix * vec4(ndcUvCenter, eps, 1);
        vec3 viewDirCenter = normalize(farPosCenter.xyz / farPosCenter.w);

        for (uint lightIdx = 0u; lightIdx < uint(MAX_LIGHT_COUNT); lightIdx++) {
            vec3 lightWorldPos = PointLightArray[lightIdx].position.xyz;
            float lightRadiusSq = PointLightArray[lightIdx].position.w;
            vec3 cameraToLight = lightWorldPos - cameraPos;

            // Calculate the distance from the camera to the point closest to the light along the view ray
            float t = dot(cameraToLight, viewDir);
            if (t < 0) {
                // If the closest point lies behind the camera, the light can only contribute to the visible
                // scene if the camera happens to be within the light's radius
                if (dot(cameraToLight, cameraToLight) > lightRadiusSq)
                    continue;

                c.g = 1;
            } else {
                const int tileSize = 16;
                float pad = tileSize * (512.f / cameraZoom) * 15;
                float paddedLightRadiusSq = pow(sqrt(lightRadiusSq) + pad, 2.f);

                // High resolution UVs
                vec3 accurateLightToClosestPoint = cameraToLight - t * viewDir;
                float accurateDistSq = dot(accurateLightToClosestPoint, accurateLightToClosestPoint);
                if (accurateDistSq < lightRadiusSq)
                    c.r = 1;

                // If the closest point lies in front of the camera, check whether the closest point along
                // the view ray lies within the light's radius
                vec3 lightToClosestPoint = cameraToLight - dot(cameraToLight, viewDirCenter) * viewDirCenter;
                float distSq = dot(lightToClosestPoint, lightToClosestPoint);
                if (distSq < lightRadiusSq) {
                    c.g = 1;
                    continue;
                }

                if (distSq < paddedLightRadiusSq) {
                    c.b = 1;
                    continue;
                }

                if (accurateDistSq < lightRadiusSq) {
                    c = vec4(1);
                    continue;
                }
            }
        }

        if (length(c) > 0)
            c.a = max(c.a, 0.3);
    #else
        ivec2 tileXY = ivec2(floor(uv * tiledLightingResolution));
        int tiledLightCount = 0;
        for (int tileLayer = 0; tileLayer < TILED_LIGHTING_LAYER_COUNT; tileLayer++) {
            ivec4 tileLayerData = texelFetch(tiledLightingArray, ivec3(tileXY, tileLayer), 0);
            for (int c = 0; c < 4; c++) {
                int lightIdx = tileLayerData[c];
                if (lightIdx <= 0)
                    break;
                tiledLightCount++;
            }
        }

        if (tiledLightCount > 0) {
            float level = tiledLightCount / float(TILED_LIGHTING_LAYER_COUNT * 4) * 3.14159265 / 2.0;
            c = vec4(sin(level), sin(level * 2), cos(level), 0.3);
        }
    #endif

    FragColor = c;
}
