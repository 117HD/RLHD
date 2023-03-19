/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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

layout (location = 0) in ivec4 vPosition;
layout (location = 1) in vec4 vUv;
layout (location = 2) in vec4 vNormal;

out vec3 gPosition;
out vec3 gUv;
out vec3 gNormal;
out vec4 gColor;
out float gFogAmount;
out int gMaterialData;
out int gTerrainData;

#include uniforms/materials.glsl

#include utils/polyfills.glsl
#include utils/constants.glsl
#include utils/color_conversion.glsl
#include utils/fog.glsl

void main() {
    int ahsl = vPosition.w;
    vec3 rgb = jagexHslToRgb(ahsl & 0xffff);
    float alpha = 1 - float(ahsl >> 24 & 0xff) / 255.;
    vec4 color = vec4(srgbToLinear(rgb), alpha);
    // CAUTION: only 24-bit ints can be stored safely as floats
    int materialData = int(vUv.w);
    int terrainData = int(vNormal.w);

    float normalMagnitude = length(vNormal.xyz);
    bool flatNormal = // Flat normals must be applied separately per vertex
        normalMagnitude == 0 ||
        (materialData >> MATERIAL_FLAG_FLAT_NORMALS & 1) == 1;

    gPosition = vec3(vPosition);
    gUv = vec3(vUv);
    gNormal = flatNormal ? vec3(0) : vNormal.xyz / normalMagnitude;
    gColor = color;
    gFogAmount = calculateFogAmount(gPosition);
    gMaterialData = materialData;
    gTerrainData = terrainData;
}
