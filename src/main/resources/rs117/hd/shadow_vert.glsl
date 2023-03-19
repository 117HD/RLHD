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

#include utils/constants.glsl

flat out vec3 gPosition;
flat out vec3 gUv;
flat out int gMaterialData;
flat out int gCastShadow;

void main() {
    gMaterialData = int(vUv.w);
    int terrainData = int(vNormal.w);
    int waterTypeIndex = terrainData >> 3 & 0x1F;
    int transparency = vPosition.w >> 24 & 0xFF;

    bool isShadowDisabled = (gMaterialData >> MATERIAL_FLAG_DISABLE_SHADOW_CASTING & 1) == 1;
    bool isGroundPlane = (terrainData & 0xF) == 1;// isTerrain && plane == 0
    bool isWaterSurfaceOrUnderwaterTile = waterTypeIndex > 0;
    bool isTransparent = transparency >= SHADOW_OPACITY_THRESHOLD * 255;
    gCastShadow = (
        isShadowDisabled ||
        isGroundPlane ||
        isWaterSurfaceOrUnderwaterTile ||
        isTransparent
    ) ? 0 : 1;

    // TODO: add hasAlphaChannel to Material, so UV calculation and frag texture fetch can be skipped
//    Material material = getMaterial(materialData >> MATERIAL_FLAG_BITS);

    gPosition = vec3(vPosition);
    gUv = vec3(vUv);
}
