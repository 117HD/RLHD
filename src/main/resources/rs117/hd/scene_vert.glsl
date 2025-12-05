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
#include VERSION_HEADER

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>
#include <uniforms/zone_data.glsl>
#include <buffers/model_data.glsl>

layout (location = 0) in vec3 vPosition;
layout (location = 1) in vec3 vUv;
layout (location = 2) in vec3 vNormal;
layout (location = 3) in int vAlphaBiasHsl;
layout (location = 4) in int vMaterialData;
layout (location = 5) in int vTerrainData;
layout (location = 6) in int vPackedZoneAndModelIdx;

#include <utils/misc.glsl>

out vec3 gPosition;
out vec3 gUv;
out vec3 gNormal;
out vec3 gSceneOffset;
out int gAlphaBiasHsl;
out int gMaterialData;
out int gTerrainData;
out int gWorldViewId;
out float gDetailFade;

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
#endif

    gPosition = vec3(getWorldViewProjection(worldViewId) * vec4(sceneOffset + vPosition, 1));
    gUv = vUv;
    gNormal = vNormal;
    gSceneOffset = sceneOffset;
    gAlphaBiasHsl = vAlphaBiasHsl;
    gMaterialData = vMaterialData;
    gTerrainData = vTerrainData;
    gWorldViewId = worldViewId;
    gDetailFade = fade;
}
