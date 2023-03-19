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

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

uniform mat4 projectionMatrix;
uniform float elapsedTime;

#include uniforms/camera.glsl

#include utils/polyfills.glsl
#include utils/constants.glsl
#define USE_VANILLA_UV_PROJECTION
#include utils/uvs.glsl

in vec3 gPosition[3];
in vec3 gUv[3];
in vec3 gNormal[3];
in vec4 gColor[3];
in float gFogAmount[3];
in int gMaterialData[3];
in int gTerrainData[3];

flat out vec4 vColor[3];
flat out vec3 vUv[3];
flat out int vMaterialData[3];
flat out int vTerrainData[3];

out FragmentData {
    vec3 position;
    vec3 normal;
    vec3 texBlend;
    float fogAmount;
} OUT;

void main() {
    // MacOS doesn't allow assigning these arrays directly.
    // One of the many wonders of Apple software...
    for (int i = 0; i < 3; i++) {
        vColor[i] = gColor[i];
        vUv[i] = gUv[i];
        vMaterialData[i] = gMaterialData[i];
        vTerrainData[i] = gTerrainData[i];
    }

    // Compute flat normals
    vec3 T = gPosition[0] - gPosition[1];
    vec3 B = gPosition[0] - gPosition[2];
    vec3 N = normalize(cross(T, B));

    computeUvs(vMaterialData[0], vec3[](gPosition[0], gPosition[1], gPosition[2]), vUv);

    for (int i = 0; i < 3; i++) {
        OUT.position = gPosition[i];
        OUT.normal = gNormal[i];
        if (OUT.normal == vec3(0))
            OUT.normal = N;
        OUT.texBlend = vec3(0);
        OUT.texBlend[i] = 1;
        OUT.fogAmount = gFogAmount[i];
        gl_Position = projectionMatrix * vec4(OUT.position, 1);
        EmitVertex();
    }

    EndPrimitive();
}
