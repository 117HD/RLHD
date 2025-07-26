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

#include <uniforms/global.glsl>
#include <uniforms/lights.glsl>

#include <utils/constants.glsl>

#include TILED_LIGHTING_LAYER

uniform isampler2DArray tiledLightingArray;

in vec3 fRay;

out ivec4 TiledData;

void main() {
    int LightMaskSize = int(ceil(pointLightsCount / 32.0));
    int LightsMask[32]; // 32 Words = 1024 Lights
    for (int i = 0; i < LightMaskSize; i++)
        LightsMask[i] = 0;

#if TILED_LIGHTING_LAYER > 0
    int LayerCount = TILED_LIGHTING_LAYER - 1;
    for (int l = LayerCount; l >= 0; l--) {
        ivec4 layerData = texelFetch(tiledLightingArray, ivec3(gl_FragCoord.xy, l), 0);
        for(int c = 4; c >= 0 ; c--) {
            int encodedLightIdx = layerData[c] - 1;
            if(encodedLightIdx < 0) {
                return; // No more lights are overlapping with cell since the previous layer didn't encode
            }

            int word = encodedLightIdx >> 5;
            int mask = 1 << (encodedLightIdx & 31);
            LightsMask[word] |= mask;
        }
    }
#endif

    const int tileSize = 16;
    float pad = tileSize * (512.f / cameraZoom) * 15;

    vec3 viewDir = normalize(fRay);
    int lightIdx = 0;
    ivec4 output = ivec4(0);
    for(int c = 0; c < 4; c++) {
        for (; lightIdx < pointLightsCount; lightIdx++) {
            vec4 lightData = PointLightArray[lightIdx].position;
            vec3 lightWorldPos = lightData.xyz;

            vec3 cameraToLight = lightWorldPos - cameraPos;
            float t = dot(cameraToLight, viewDir);
            if (t < 0)
                continue; // Closest point is behind the camera

            // Check if the camera is outside of the light's radiusv
            float lightRadiusSq = lightData.w;
            if (dot(cameraToLight, cameraToLight) > lightRadiusSq) {
                vec3 lightToClosestPoint = cameraToLight - t * viewDir;
                float dist = length(lightToClosestPoint);
                dist = max(0, dist - pad);

                if (dist * dist > lightRadiusSq)
                    continue; // View ray doesn't intersect with the light's sphere
            }

            int word = lightIdx >> 5;
            int mask = 1 << (lightIdx & 31);
            if ((LightsMask[word] & mask) != 0)
                continue;

            output[c] = lightIdx + 1;
            LightsMask[word] |= mask;
            break;
        }
    }

    TiledData = output;
}
