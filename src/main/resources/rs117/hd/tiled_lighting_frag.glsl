/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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

in vec3 fRay;

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

    const int tileSize = 16;
    float pad = tileSize * (512.f / cameraZoom) * 15;

    vec2 screenUV = gl_FragCoord.xy;
    vec3 viewDir = normalize(fRay);
    int lightIdx = 0;
#if TILED_IMAGE_STORE
    for (int l = 0; l < TILED_LIGHTING_LAYER_COUNT; l++)
#endif
    {
        ivec4 outputTileData = ivec4(0);
        for (int c = 0; c < 4; c++) {
            for (; lightIdx < pointLightsCount; lightIdx++) {
                vec4 lightData = PointLightArray[lightIdx].position;
                vec3 lightWorldPos = lightData.xyz;
                vec3 cameraToLight = lightWorldPos - cameraPos;
                float paddedLightRadiusSq = lightData.w;

                // Calculate the distance from the camera to the point closest to the light along the view ray
                float t = dot(cameraToLight, viewDir);
                if (t < 0) {
                    // If the closest point lies behind the camera, the light can only contribute to the visible
                    // scene if the camera happens to be within the light's radius
                    if (dot(cameraToLight, cameraToLight) > paddedLightRadiusSq)
                        continue;
                } else {
                    // If the closest point lies in front of the camera, check whether the closest point along
                    // the view ray lies within the light's radius
                    vec3 lightToClosestPoint = cameraToLight - t * viewDir;
                    float distSq = dot(lightToClosestPoint, lightToClosestPoint);
                    if (distSq > paddedLightRadiusSq)
                        continue;
                }

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
                imageStore(tiledLightingImage, ivec3(screenUV, l), outputTileData);

            if (lightIdx >= pointLightsCount)
                return;
        #else
            TiledData = outputTileData;
        #endif
    }
}
