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

layout (location = 0) in ivec4 vPosition;
layout (location = 1) in vec4 vUv;
layout (location = 2) in vec4 vNormal;

uniform mat4 lightProjectionMatrix;

out vec3 position;
out vec3 uvw;
flat out int materialData;

#include utils/constants.glsl

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
    position = vPosition.xyz;
    uvw = vUv.xyz;
    materialData = int(vUv.w);
    int terrainData = int(vNormal.w);

    float alpha = 1 - float(vPosition.w >> 24 & 0xff) / 255.;
    int waterTypeIndex = terrainData >> 3 & 0x1F;

    int isShadowDisabled = materialData >> MATERIAL_FLAG_DISABLE_SHADOWS & 1;
    int isGroundPlane = when_eq(terrainData & 0xF, 1); // isTerrain && plane == 0
    int isTransparent = when_lt(alpha, SHADOW_OPACITY_THRESHOLD);
    int isWaterSurfaceOrUnderwaterTile = when_gt(waterTypeIndex, 0);
    position *= 1 - max(0, sign(isShadowDisabled + isGroundPlane + isTransparent + isWaterSurfaceOrUnderwaterTile));

    gl_Position = lightProjectionMatrix * vec4(position, 1.f);
}
