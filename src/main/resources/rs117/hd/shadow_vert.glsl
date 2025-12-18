/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
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
#include <uniforms/world_views.glsl>
#include <uniforms/materials.glsl>

#include <utils/constants.glsl>

layout (location = 0) in vec3 vPosition;
layout (location = 1) in vec3 vUv;

#if ZONE_RENDERER
layout (location = 3) in int vTextureFaceIdx;

layout (location = 6) in int vWorldViewId;
layout (location = 7) in ivec2 vSceneBase;

uniform isamplerBuffer textureFaces;

#if SHADOW_MODE == SHADOW_MODE_DETAILED
    out vec3 fUvw;
    flat out int fMaterialData;
#endif

#if SHADOW_TRANSPARENCY
    // Pass to fragment shader
    out float fOpacity;
#endif

void main() {
    int vertex = int(vUv.z);
    int alphaBiasHsl = texelFetch(textureFaces, vTextureFaceIdx)[vertex];
    int materialData = texelFetch(textureFaces, vTextureFaceIdx + 1)[vertex];
    int terrainData = texelFetch(textureFaces, vTextureFaceIdx + 2)[vertex];

    int waterTypeIndex = terrainData >> 3 & 0xFF;
    float opacity = 1 - (alphaBiasHsl >> 24 & 0xFF) / float(0xFF);

    float opacityThreshold = float(materialData >> MATERIAL_SHADOW_OPACITY_THRESHOLD_SHIFT & 0x3F) / 0x3F;
    if (opacityThreshold == 0)
        opacityThreshold = SHADOW_DEFAULT_OPACITY_THRESHOLD;

    bool isTransparent = opacity <= opacityThreshold;
    bool isGroundPlaneTile = (terrainData & 0xF) == 1; // plane == 0 && isTerrain
    bool isWaterSurfaceOrUnderwaterTile = waterTypeIndex > 0;

    bool isShadowDisabled =
        isGroundPlaneTile ||
        isWaterSurfaceOrUnderwaterTile ||
        isTransparent;

    if(!isShadowDisabled && vWorldViewId > 0) {
        ivec4 tint = getWorldViewTint(vWorldViewId);
        if(tint.x != -1 && tint.y != -1 && tint.z != -1) {
            isShadowDisabled = true;
        }
    }

#if SHADOW_MODE == SHADOW_MODE_DETAILED
    if(!isShadowDisabled) {
        Material material = getMaterial(materialData >> MATERIAL_INDEX_SHIFT & MATERIAL_INDEX_MASK);

        fUvw = vec3(vUv.xy, material.colorMap);
        // Scroll UVs
        fUvw.xy += material.scrollDuration * elapsedTime;
        // Scale from the center
        fUvw.xy = .5 + (fUvw.xy - .5) * material.textureScale.xy;
    } else {
        // All outputs must be written for Mac compatibility, even if unused
        fUvw = vec3(0);
    }
    fMaterialData = materialData;
#endif

    int shouldCastShadow = isShadowDisabled ? 0 : 1;

    vec3 sceneOffset = vec3(vSceneBase.x, 0, vSceneBase.y);
    vec3 worldPosition = sceneOffset + vPosition;
    if(vWorldViewId != -1) {
        mat4x3 worldViewProjection = mat4x3(getWorldViewProjection(vWorldViewId));
        worldPosition = worldViewProjection * vec4(worldPosition, 1.0);;
    }

#if SHADOW_TRANSPARENCY
    fOpacity = opacity;
#endif
    gl_Position = lightProjectionMatrix * vec4(worldPosition, shouldCastShadow);
}
#else

layout (location = 3) in int vAlphaBiasHsl;
layout (location = 4) in int vMaterialData;
layout (location = 5) in int vTerrainData;

#if SHADOW_MODE == SHADOW_MODE_DETAILED
    // Pass to geometry shader
    flat out vec3 gPosition;
    flat out vec3 gUv;
    flat out int gMaterialData;
    flat out int gCastShadow;
    flat out int gWorldViewId;
    #if SHADOW_TRANSPARENCY
        flat out float gOpacity;
    #endif
#else
    #if SHADOW_TRANSPARENCY
        // Pass to fragment shader
        out float fOpacity;
    #endif
#endif

void main() {
    int waterTypeIndex = vTerrainData >> 3 & 0xFF;
    float opacity = 1 - (vAlphaBiasHsl >> 24 & 0xFF) / float(0xFF);

    float opacityThreshold = float(vMaterialData >> MATERIAL_SHADOW_OPACITY_THRESHOLD_SHIFT & 0x3F) / 0x3F;
    if (opacityThreshold == 0)
        opacityThreshold = SHADOW_DEFAULT_OPACITY_THRESHOLD;

    bool isTransparent = opacity <= opacityThreshold;
    bool isGroundPlaneTile = (vTerrainData & 0xF) == 1; // plane == 0 && isTerrain
    bool isWaterSurfaceOrUnderwaterTile = waterTypeIndex > 0;

    bool isShadowDisabled =
        isGroundPlaneTile ||
        isWaterSurfaceOrUnderwaterTile ||
        isTransparent;

    int shouldCastShadow = isShadowDisabled ? 0 : 1;

    #if SHADOW_MODE == SHADOW_MODE_DETAILED
        gPosition = vPosition;
        gUv = vUv;
        gMaterialData = vMaterialData;
        gCastShadow = shouldCastShadow;
        #if SHADOW_TRANSPARENCY
            gOpacity = opacity;
        #endif
    #else
        gl_Position = lightProjectionMatrix * vec4(vPosition, shouldCastShadow);
        #if SHADOW_TRANSPARENCY
            fOpacity = opacity;
        #endif
    #endif
}

#endif
