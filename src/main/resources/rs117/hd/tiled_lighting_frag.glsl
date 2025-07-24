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

#include <uniforms/lights.glsl>
#include <utils/constants.glsl>

#include LAYER

layout(std140) uniform UBOTiledLights {
    int tileCountX;
    int tileCountY;
    int pointLightsCount;
    vec3 cameraPos;
    mat4 invProjectionMatrix;
};

uniform isampler2DArray tiledLightingArray;

in vec2 TexCoord;
in vec2 quadPos;

out uint TiledCellIndex;

uint LightsMask[32] = uint[](0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u); // 32 * 32 = 1024 lights

void main() {
    ivec2 tileCoord = ivec2(vec2(TexCoord.x, 1.0 - TexCoord.y) * vec2(tileCountX, tileCountY));

    // If we're not the first layer, then check the last layer if it wrote anything otherwise theres no work left to do
    #if LAYER > 0
    {
        mediump int lightIdx = texelFetch(tiledLightingArray, ivec3(tileCoord, LAYER - 1), 0).r - 1;
        if (lightIdx < 0) {
            discard;
        }
        LightsMask[lightIdx / 32] |= (1u << (lightIdx % 32));
    }
    #endif

    #if LAYER > 1
    for(int l = LAYER - 2; l >= 0; l--) {
        mediump uint lightIdx = uint(texelFetch(tiledLightingArray, ivec3(tileCoord, l), 0).r - 1);
        LightsMask[lightIdx >> 5] |= 1u << (lightIdx & 31u);
    }
    #endif

    vec4 worldPos = invProjectionMatrix * vec4(quadPos, 1.0, 1.0);
    vec3 worldViewDir = normalize((worldPos.xyz / worldPos.w) - cameraPos);

    for (uint lightIdx = 0u; lightIdx < uint(MAX_LIGHT_COUNT); lightIdx++) {
        vec3 lightWorldPos = PointLightArray[lightIdx].position.xyz;
        float lightRadius = PointLightArray[lightIdx].position.w;

        vec3 lightToCamera = cameraPos - lightWorldPos;
        float dist = dot(lightToCamera, lightToCamera);
        float vDotL = dot(worldViewDir, lightToCamera);
        float discriminant = vDotL * vDotL - dist;

        if (discriminant >= -lightRadius) {
            if ((LightsMask[lightIdx >> 5] & (1u << (lightIdx & 31u))) != 0u) {
                continue; // Already seen
            }

            // Light hasn't been added to a previous layer, therefore we can add it and early out of the fragment shader
            TiledCellIndex = lightIdx + 1u;
            return;
        }
    }

    // Intersected with no light
    TiledCellIndex = 0u;
}
