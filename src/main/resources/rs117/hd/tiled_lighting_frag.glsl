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

out uint TiledCellIndex;

uint LightsMask[32] = uint[](
    0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
    0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
    0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u,
    0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u
); // 32 * 32 = 1024 lights

void main() {
    // If we're not the first layer, then check the last layer if it wrote anything otherwise theres no work left to do
    #if TILED_LIGHTING_LAYER > 0
    {
        int lightIdx = texelFetch(tiledLightingArray, ivec3(gl_FragCoord.xy, TILED_LIGHTING_LAYER - 1), 0).r - 1;
        if (lightIdx < 0)
            discard;
        LightsMask[lightIdx / 32] |= (1u << (lightIdx % 32));
    }
    #endif

    #if TILED_LIGHTING_LAYER > 1
        for (int l = TILED_LIGHTING_LAYER - 2; l >= 0; l--) {
            uint lightIdx = uint(texelFetch(tiledLightingArray, ivec3(gl_FragCoord.xy, l), 0).r - 1);
            LightsMask[lightIdx >> 5] |= 1u << (lightIdx & 31u);
        }
    #endif

    vec3 viewDir = normalize(fRay);

    for (uint lightIdx = 0u; lightIdx < uint(MAX_LIGHT_COUNT); lightIdx++) {
        vec3 lightWorldPos = PointLightArray[lightIdx].position.xyz;
        float lightRadiusSq = PointLightArray[lightIdx].position.w;

        vec3 cameraToLight = lightWorldPos - cameraPos;
        // Check if the camera is outside of the light's radius
        if (dot(cameraToLight, cameraToLight) > lightRadiusSq) {
            float t = dot(cameraToLight, viewDir);
            if (t < 0)
                continue; // Closest point is behind the camera
            vec3 lightToClosestPoint = cameraToLight - t * viewDir;
            float dist = length(lightToClosestPoint);
            const int tileSize = 16;
            float pad = tileSize * (512.f / cameraZoom) * 15;
            dist = max(0, dist - pad);
            if (dist * dist > lightRadiusSq)
                continue; // View ray doesn't intersect with the light's sphere
        }

        if ((LightsMask[lightIdx >> 5] & (1u << (lightIdx & 31u))) == 0u) {
            // Light hasn't been added to a previous layer, therefore we can add it and early out of the fragment shader
            TiledCellIndex = lightIdx + 1u;
            return;
        }
    }

    // Intersected with no light
    TiledCellIndex = 0u;
}
