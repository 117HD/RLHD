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

uniform isampler2DArray tiledLightingArray;
uniform int layer;

in vec2 TexCoord;
in vec2 quadPos;

out int TiledCellIndex;

void main() {
    vec4 clip = vec4(quadPos, 1.0, 1.0);
    vec4 world = invProjectionMatrix * clip;
    vec3 worldViewDir = normalize((world.xyz / world.w) - cameraPos);

    // Cache all the previous layer indicies
    int layerLightIndicies[MAX_LIGHTS_PER_TILE];
    for(int l = 0; l < layer; l++) {
        int lightIdx = texelFetch(tiledLightingArray, ivec3(TexCoord * vec2(tileXCount, tileYCount), l), 0).r;

        if (lightIdx == 0) {
            // A previous layer didn't overlap with any lights — early out
            TiledCellIndex = 0;
            return;
        }

        layerLightIndicies[l] = lightIdx - 1;
    }

    for (int lightIDx = 0; lightIDx < pointLightsCount; lightIDx++) {
        vec3 lightWorldPos = PointLightArray[lightIDx].position.xyz;
        float lightRadius = sqrt(PointLightArray[lightIDx].position.w);

        vec3 lightToCamera = cameraPos - lightWorldPos;
        float b = dot(worldViewDir, lightToCamera);
        float c = dot(lightToCamera, lightToCamera) - lightRadius * lightRadius;
        float discriminant = b * b - c;

        if (discriminant >= 0.0) {
            bool alreadyIntersected = false;
            for(int l = 0; l < layer; l++) {
                if(layerLightIndicies[l] == lightIDx){
                    alreadyIntersected = true;
                    break;
                }
            }
            if(alreadyIntersected) {
                continue; // Keep looking for a light
            }

            // Light hasn't been added to a previous layer, therefore we can add it and early out of the fragment shader
            TiledCellIndex = lightIDx + 1;
            return;
        }
    }

    // Intersected with no light
    TiledCellIndex = 0;
}
