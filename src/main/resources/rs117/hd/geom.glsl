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

#define PI 3.1415926535897932384626433832795f
#define UNIT PI / 1024.0f

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

#include utils/polyfills.glsl
#include utils/constants.glsl
#include utils/misc.glsl

uniform mat4 projectionMatrix;
uniform mat4 lightProjectionMatrix;

in VertexData {
    ivec3 pos;
    vec4 normal;
    vec4 color;
    vec4 uv;
    float fogAmount;
} IN[3];

flat out vec4 vColor[3];
flat out vec3 vUv[3];
flat out int vMaterialData[3];
flat out int vTerrainData[3];

out FragmentData {
    float fogAmount;
    vec3 normal;
    vec3 position;
    vec3 texBlend;
} OUT;

void main() {
    int materialData = int(IN[0].uv.w);
    bool flatNormals =
        length(IN[0].normal.xyz) < .01 ||
        (materialData >> MATERIAL_FLAG_FLAT_NORMALS & 1) == 1;

    // Compute flat normals
    vec3 T = vec3(IN[0].pos - IN[1].pos);
    vec3 B = vec3(IN[0].pos - IN[2].pos);
    vec3 N = normalize(cross(T, B));

    for (int i = 0; i < 3; i++) {
        vColor[i] = IN[i].color;
        vUv[i] = IN[i].uv.xyz;
        vMaterialData[i] = int(IN[i].uv.w);
        vTerrainData[i] = int(IN[i].normal.w);
    }

    for (int i = 0; i < 3; i++) {
        OUT.texBlend = vec3(0);
        OUT.texBlend[i] = 1;
        OUT.fogAmount = IN[i].fogAmount;
        OUT.position = IN[i].pos;
        OUT.normal = flatNormals ? N : normalize(IN[i].normal.xyz);
        gl_Position = projectionMatrix * vec4(IN[i].pos, 1.f);
        EmitVertex();
    }

    EndPrimitive();
}
