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

#include utils/constants.glsl

layout (location = 0) in ivec4 VertexPosition;
layout (location = 1) in vec4 uv;
layout (location = 2) in vec4 normal;

uniform mat4 lightProjectionMatrix;

out float alpha;
out vec2 fUv;
flat out int materialId;

int when_eq(int x, int y) {
    return 1 - abs(sign(x - y));
}

int when_lt(float x, float y) {
    return max(int(sign(y - x)), 0);
}

int when_gt(int x, int y) {
    return max(sign(x - y), 0);
}

void main()
{
    ivec3 vertex = VertexPosition.xyz;
    int materialData = int(uv.x);
    int terrainData = int(normal.w);

    fUv = uv.yz;
    alpha = 1 - float(VertexPosition.w >> 24 & 0xff) / 255.;
    materialId = materialData >> 3;

    int waterTypeIndex = terrainData >> 3 & 0x1F;

    int isShadowDisabled = materialData & 1;
    int isGroundPlane = when_eq(terrainData & 0xF, 1); // isTerrain && plane == 0
    int isTransparent = when_lt(alpha, SHADOW_OPACITY_THRESHOLD);
    int isWaterSurfaceOrUnderwaterTile = when_gt(waterTypeIndex, 0);
    vertex *= 1 - max(0, sign(isShadowDisabled + isGroundPlane + isTransparent + isWaterSurfaceOrUnderwaterTile));

    gl_Position = lightProjectionMatrix * vec4(vertex, 1.f);
}
