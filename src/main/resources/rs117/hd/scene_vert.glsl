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

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>
#include <uniforms/texture_faces.glsl>
#include <uniforms/model_data.glsl>
#include <uniforms/displacement.glsl>

#include <utils/constants.glsl>
#include <utils/uvs.glsl>
#include <utils/misc.glsl>
#include <utils/wind_character_displacement.glsl>

layout (location = 0) in vec3 vPosition;

#if ZONE_RENDERER
    layout (location = 1) in vec4 vUv;
    layout (location = 2) in vec4 vNormal;
    layout (location = 3) in int vPackedTextureFace;
    layout (location = 6) in int vWorldViewId;
    layout (location = 7) in ivec2 vSceneBase;
    layout (location = 8) in float vFade;
#else
    layout (location = 1) in vec3 vUv;
    layout (location = 2) in vec3 vNormal;
    layout (location = 3) in int vAlphaBiasHsl;
    layout (location = 4) in int vMaterialData;
    layout (location = 5) in int vTerrainData;
#endif

#if ZONE_RENDERER
    flat out int fWorldViewId;
    flat out float fFade;
    flat out ivec3 fAlphaBiasHsl;
    flat out ivec3 fMaterialData;
    flat out ivec3 fTerrainData;

    #if FLAT_SHADING
        flat out vec3 fFlatNormal;
    #endif

    out FragmentData {
        vec3 position;
        vec2 uv;
        vec3 normal;
        vec3 texBlend;
    } OUT;

    void main() {
        int vertex = gl_VertexID % 3;
        int alphaBiasHsl;
        int materialData;

        if(isModelFace(vPackedTextureFace)) {
            ModelFaceData faceData = getModelFaceData(getFaceOffset(vPackedTextureFace));
            fAlphaBiasHsl = faceData.AlphaBiasHsl;
            fMaterialData = ivec3(faceData.MaterialData);
            fTerrainData = ivec3(0);
            alphaBiasHsl = faceData.AlphaBiasHsl[vertex];
            materialData = faceData.MaterialData;
        } else {
            StaticFaceData faceData = getStaticFaceData(getFaceOffset(vPackedTextureFace));
            fAlphaBiasHsl = faceData.AlphaBiasHsl;
            fMaterialData = faceData.MaterialData;
            fTerrainData = faceData.TerrainData;
            alphaBiasHsl = faceData.AlphaBiasHsl[vertex];
            materialData = faceData.MaterialData[vertex];
            fFade        = vFade;
        }

        int worldViewIdx = vWorldViewId;
        vec3 sceneOffset = vec3(vSceneBase.x, 0, vSceneBase.y);
        ModelData modelData;

        int modelIdx = int(vNormal.w);
        if (modelIdx > 0) {
            modelData = getModelData(modelIdx);
            fFade     = max(modelData.fade, fFade);
            if(isModelDynamic(modelData)) {
                worldViewIdx = modelData.worldViewIdx;
                sceneOffset = modelData.position;
            }
        }

        vec3 worldNormal = vNormal.xyz;
        vec3 worldPosition = vPosition + sceneOffset;

        if (modelIdx > 0) {
            ObjectWindSample windSample = computeWindSample(modelData.position, modelData.height);
            worldPosition += applyWindDisplacementVertex(
                windSample,
                materialData,
                float(modelData.height),
                worldPosition,
                worldPosition - modelData.position,
                vNormal.xyz
            );
        } else {
            // Clamp underwater vertices to the water surface along the draw distance border, excluding
            // waterDepth == 1, which is used when the geometry already sits flush with the surface
            int waterDepth = fTerrainData[vertex] >> 11 & 0xFFF;
            if (waterDepth > 1) {
                const int TILE_SIZE = 128;
                const int CHUNK_SIZE = TILE_SIZE * 8;
                ivec2 cam = ivec2(cameraPos.xz / CHUNK_SIZE) * CHUNK_SIZE + CHUNK_SIZE / 2;
                ivec2 d = ivec2(abs(worldPosition.xz - cam) / TILE_SIZE);
                if (max(d.x, d.y) > int(drawDistance / 8) * 8 + 3)
                    worldPosition.y -= waterDepth;
            }
        }

        if (worldViewIdx != -1) {
            mat4x3 worldViewProjection = mat4x3(getWorldViewProjection(worldViewIdx));
            worldPosition = worldViewProjection * vec4(worldPosition, 1.0);
            worldNormal = mat3(worldViewProjection) * worldNormal;
        }
        fWorldViewId = worldViewIdx;

        OUT.position = worldPosition;
        OUT.uv = computeVertexUvs(materialData, worldPosition, vUv.xyz);
        OUT.normal = worldNormal;
        OUT.texBlend = vec3(0);
        OUT.texBlend[vertex] = 1.0;

        #if FLAT_SHADING
            fFlatNormal = worldNormal;
        #endif

        vec4 clipPosition = projectionMatrix * vec4(worldPosition, 1.0);
        int depthBias = (alphaBiasHsl >> 16) & 0xff;
        if (projectionMatrix[2][3] != 0) // Disable depth bias for orthographic projection
            clipPosition.z += depthBias / 128.0;

        gl_Position = clipPosition;
    }
#else
    out vec3 gPosition;
    out vec3 gUv;
    out vec3 gNormal;
    out int gAlphaBiasHsl;
    out int gMaterialData;
    out int gTerrainData;

    void main() {
        gPosition = vPosition;
        gUv = vUv.xyz;
        gNormal = vNormal.xyz;
        gAlphaBiasHsl = vAlphaBiasHsl;
        gMaterialData = vMaterialData;
        gTerrainData = vTerrainData;
    }
#endif
