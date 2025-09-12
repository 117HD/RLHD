#version 330

#include TILED_LIGHTING_LAYER
#include TILED_IMAGE_STORE

#if TILED_IMAGE_STORE
#extension GL_EXT_shader_image_load_store : require

layout(rgba16i) uniform iimage2DArray tiledLightingImage;
#else
uniform isampler2DArray tiledLightingArray;

out ivec4 TiledData;
#endif

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

#include <utils/constants.glsl>

in vec2 fUv;

void main() {
    int LightMaskSize = int(ceil(pointLightsCount / 32.0));
    uint LightsMask[32]; // 32 Words = 1024 Lights
    for (int i = 0; i < LightMaskSize; i++)
        LightsMask[i] = 0u;

    #if TILED_LIGHTING_LAYER > 0 && !TILED_IMAGE_STORE
        int LayerCount = TILED_LIGHTING_LAYER - 1;
        for (int l = LayerCount; l >= 0; l--) {
            ivec4 layerData = texelFetch(tiledLightingArray, ivec3(gl_FragCoord.xy, l), 0);
            for (int c = 4; c >= 0 ; c--) {
                int encodedLightIdx = layerData[c] - 1;
                if (encodedLightIdx < 0) {
                    TiledData = ivec4(0.0);
                    return; // No more lights are overlapping with cell since the previous layer didn't encode
                }

                uint word = uint(encodedLightIdx) >> 5u;
                uint mask = 1u << (uint(encodedLightIdx) & 31u);
                LightsMask[word] |= mask;
            }
        }
    #endif

    const vec2 tileSize = vec2(TILED_LIGHTING_TILE_SIZE);
    vec2 screenUV = fUv * sceneResolution;
    vec2 tileOrigin = floor(screenUV / tileSize) * tileSize;

    vec2 tl = tileOrigin + vec2(0.0, tileSize.y); // top-left
    vec2 tr = tileOrigin + tileSize;              // top-right
    vec2 bl = tileOrigin;                         // bottom-left
    vec2 br = tileOrigin + vec2(tileSize.x, 0.0); // bottom-right

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
    float tileSin = sqrt(1.0 - tileCos * tileCos);

    int lightIdx = 0;
#if TILED_IMAGE_STORE
    for (int l = 0; l < TILED_LIGHTING_LAYER_COUNT; l++)
#endif
    {
        ivec4 outputTileData = ivec4(0);
        for (int c = 0; c < 4; c++) {
            for (; lightIdx < pointLightsCount; lightIdx++) {
                vec4 lightData = PointLightPositionsArray[lightIdx];
                vec3 lightViewPos = lightData.xyz;
                float lightRadiusSqr = lightData.w;

                float lightDistSqr = dot(lightViewPos, lightViewPos);

                vec3 lightCenterVec = (lightDistSqr > 0.0) ? lightViewPos / sqrt(lightDistSqr) : vec3(0.0);

                float lightSinSqr = clamp(lightRadiusSqr / max(lightDistSqr, 1e-6), 0.0, 1.0);
                float lightCos = sqrt(0.999 - lightSinSqr);
                float lightTileCos = dot(lightCenterVec, tileCenterVec);

                float sumCos = (lightRadiusSqr > lightDistSqr) ? -1.0 : (tileCos * lightCos - tileSin * sqrt(lightSinSqr));
                if (lightTileCos < sumCos)
                    continue;

                uint word = uint(lightIdx) >> 5u;
                uint mask = 1u << (uint(lightIdx) & 31u);
                if ((LightsMask[word] & mask) != 0u)
                    continue;

                outputTileData[c] = lightIdx + 1;
                LightsMask[word] |= mask;
                break;
            }
        }

        #if TILED_IMAGE_STORE
            if (outputTileData != ivec4(0))
                imageStore(tiledLightingImage, ivec3(gl_FragCoord.xy, l), outputTileData);

            if (lightIdx >= pointLightsCount)
                return;
        #else
            TiledData = outputTileData;
        #endif
    }
}
