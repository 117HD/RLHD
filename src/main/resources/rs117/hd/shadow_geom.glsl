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
#include utils/vanilla_uvs.glsl

uniform mat4 lightProjectionMatrix;

in VertexData {
    ivec4 position;
    vec4 uv;
    vec4 normal;
} IN[3];

out vec3 position;
out vec3 uvw;
flat out int materialData;

void main() {
    materialData = int(IN[0].uv.w);
    int terrainData = int(IN[0].normal.w);
    int waterTypeIndex = terrainData >> 3 & 0x1F;

    bool isShadowDisabled = (materialData >> MATERIAL_FLAG_DISABLE_SHADOW_CASTING & 1) == 1;
    bool isGroundPlane = (terrainData & 0xF) == 1;// isTerrain && plane == 0
    bool isWaterSurfaceOrUnderwaterTile = waterTypeIndex > 0;
    isShadowDisabled = isShadowDisabled || isGroundPlane || isWaterSurfaceOrUnderwaterTile;

    if (isShadowDisabled)
        return;

    vec3 uvs[3] = vec3[](IN[0].uv.xyz, IN[1].uv.xyz, IN[2].uv.xyz);
    if ((materialData >> MATERIAL_FLAG_IS_VANILLA_TEXTURED & 1) == 1) {
        compute_uv(
            IN[0].position.xyz, IN[1].position.xyz, IN[2].position.xyz,
            uvs[0], uvs[1], uvs[2]
        );
    }

    for (int i = 0; i < 3; i++) {
        position = IN[i].position.xyz;
        uvw = uvs[i];
        gl_Position = lightProjectionMatrix * vec4(position, 1.f);

        float transparency = float(IN[i].position.w >> 24 & 0xff) / 255.;
        bool castShadow = transparency < SHADOW_OPACITY_THRESHOLD;
        if (castShadow)
            EmitVertex();
    }

    EndPrimitive();
}
