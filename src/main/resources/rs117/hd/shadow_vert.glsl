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
#include VERSION_HEADER

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>
#include <uniforms/zone_data.glsl>
#include <buffers/model_data.glsl>

// Vertex Data
layout (location = 0) in vec3 vPosition;
layout (location = 1) in vec3 vUv;
layout (location = 3) in int vAlphaBiasHsl;
layout (location = 4) in int vMaterialData;
layout (location = 5) in int vTerrainData;
layout (location = 6) in int vPackedZoneAndModelIdx;

#include <utils/constants.glsl>
#include <utils/misc.glsl>

#if SHADOW_MODE == SHADOW_MODE_DETAILED
    // Pass to geometry shader
    flat out vec3 gPosition;
    flat out vec3 gUv;
    flat out int gMaterialData;
    flat out int gCastShadow;
    flat out int gWorldViewId;
    flat out float gDetailFade;
    #if SHADOW_TRANSPARENCY
        flat out float gOpacity;
    #endif
#else
    #if SHADOW_TRANSPARENCY
        // Pass to fragment shader
        out float fOpacity;
    #endif
    out float fDetailFade;
#endif

void main() {
    int worldViewId = 0;
    vec3 sceneOffset = vec3(0.0);
    float fade = 0.0f;

#if ZONE_RENDERER
    int zoneIdx = vPackedZoneAndModelIdx & 0xFFF;
    int modelIdx = vPackedZoneAndModelIdx >> 12;

    if(zoneIdx > 0) {
        worldViewId = getZoneWorldViewIdx(zoneIdx);
        sceneOffset = getZoneSceneOffset(zoneIdx);
        fade = getZoneReveal(zoneIdx);
    }

   if(modelIdx > 0) {
       ModelData modelData = getModelData(modelIdx);
       if(!isStaticModel(modelData)) {
           sceneOffset = vec3(0.0);
       }

       if(isDetailModel(modelData)) {
           float modelFade = 0.0;
           getDetailCullingFade(modelData, sceneOffset, modelFade);
           fade = max(fade, modelFade);
       }
   }

    #if SHADOW_MODE == SHADOW_MODE_DETAILED
        gDetailFade = fade;
    #else
        fDetailFade = fade;
   #endif
#endif

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
        isTransparent ||
        fade == 1.0f;

#if ZONE_RENDERER
    if(!isShadowDisabled && worldViewId > 0) {
        ivec4 tint = getWorldViewTint(worldViewId);
        if(tint.x != -1 && tint.y != -1 && tint.z != -1) {
            isShadowDisabled = true;
        }
    }
#endif

    vec3 pos = sceneOffset + vPosition;
    int shouldCastShadow = isShadowDisabled ? 0 : 1;

    #if SHADOW_MODE == SHADOW_MODE_DETAILED
        gPosition = pos;
        gUv = vUv;
        gMaterialData = vMaterialData;
        gCastShadow = shouldCastShadow;
        gWorldViewId = worldViewId;
        #if SHADOW_TRANSPARENCY
            gOpacity = opacity;
        #endif
    #else
        gl_Position = lightProjectionMatrix * getWorldViewProjection(worldViewId) * vec4(pos, shouldCastShadow);
        #if SHADOW_TRANSPARENCY
            fOpacity = opacity;
        #endif
    #endif
}
