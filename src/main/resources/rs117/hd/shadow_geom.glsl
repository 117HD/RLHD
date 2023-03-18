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

uniform mat4 lightProjectionMatrix;
uniform float elapsedTime;

#include uniforms/materials.glsl

#include utils/polyfills.glsl
#include utils/constants.glsl
#include utils/misc.glsl
#include utils/uvs.glsl

in VertexData {
    ivec4 position;
    vec4 uv;
    vec4 normal;
} IN[3];

out vec3 fUvw;

void main() {
    int materialData = int(IN[0].uv.w);
    int terrainData = int(IN[0].normal.w);
    int waterTypeIndex = terrainData >> 3 & 0x1F;
    int transparency = 0;
    for (int i = 0; i < 3; i++)
        transparency += IN[i].position.w >> 24 & 0xff;

    bool isShadowDisabled = (materialData >> MATERIAL_FLAG_DISABLE_SHADOW_CASTING & 1) == 1;
    bool isGroundPlane = (terrainData & 0xF) == 1;// isTerrain && plane == 0
    bool isWaterSurfaceOrUnderwaterTile = waterTypeIndex > 0;
    bool isTransparent = transparency >= SHADOW_OPACITY_THRESHOLD * 3 * 255;
    if (isShadowDisabled || isGroundPlane || isWaterSurfaceOrUnderwaterTile || isTransparent)
        return;

    // TODO: add hasAlphaChannel to Material, so UV calculation and frag texture fetch can be skipped
    Material material = getMaterial(materialData >> MATERIAL_FLAG_BITS);
    vec3 pos[3] = vec3[](
        IN[0].position.xyz,
        IN[1].position.xyz,
        IN[2].position.xyz
    );
    vec3 uvw[3] = vec3[](
        IN[0].uv.xyz,
        IN[1].uv.xyz,
        IN[2].uv.xyz
    );
    computeUvs(material, materialData, pos, uvw);

    for (int i = 0; i < 3; i++) {
        fUvw = uvw[i];
        fUvw.z = material.colorMap;
        // Scroll UVs
        fUvw.xy += material.scrollDuration * elapsedTime;
        // Scale from the center
        fUvw.xy = .5 + (fUvw.xy - .5) / material.textureScale;
        gl_Position = lightProjectionMatrix * vec4(IN[i].position.xyz, 1.f);
        EmitVertex();
    }

    EndPrimitive();
}
