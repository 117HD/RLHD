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

#include <utils/constants.glsl>
#include <utils/uvs.glsl>

layout (location = 0) in vec3 vPosition;

#if ZONE_RENDERER
    layout (location = 1) in vec4 vUv;
    layout (location = 2) in vec4 vNormal;
    layout (location = 3) in int vTextureFaceIdx;
    layout (location = 6) in int vWorldViewId;
    layout (location = 7) in ivec2 vSceneBase;

    uniform isamplerBuffer textureFaces;

    #include <scene_common.glsl>
#else
    layout (location = 1) in vec3 vUv;
    layout (location = 2) in vec3 vNormal;
    layout (location = 3) in int vAlphaBiasHsl;
    layout (location = 4) in int vMaterialData;
    layout (location = 5) in int vTerrainData;
#endif

#if ZONE_RENDERER
    flat out int fWorldViewId;
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

    out float vViewZ;

    void main() {
        SceneVertex sv = resolveSceneVertex(vPosition, vTextureFaceIdx, vSceneBase, vWorldViewId);
        int vertex = sv.vertex;
        int faceIdx = sv.faceIdx;
        bool isProvoking = gl_VertexID % 3 == 2;

        int materialData = 0;
        int alphaBiasHsl = 0;
        if (isProvoking) {
            // Only the Provoking vertex needs to fetch the face data
            fAlphaBiasHsl = sv.faceAlphaBiasHsl;
            fMaterialData = sv.faceMaterialData;
            fWorldViewId = vWorldViewId;
            alphaBiasHsl = fAlphaBiasHsl[vertex];
            materialData = fMaterialData[vertex];
        } else {
            // All outputs must be written to for macOS compatibility
            fAlphaBiasHsl = ivec3(0);
            fMaterialData = ivec3(0);
            fWorldViewId  = 0;
            alphaBiasHsl = sv.faceAlphaBiasHsl[vertex];
            materialData = sv.faceMaterialData[vertex];
        }
        fTerrainData = sv.faceTerrainData;

        vec3 worldNormal = vNormal.xyz;
        vec3 worldPosition = sv.worldPosition;
        if (sv.hasWorldView)
            worldNormal = mat3(sv.worldViewProjection) * worldNormal;

        // Clamp underwater vertices to the water surface along the draw distance border, excluding
        // waterDepth == 1, which is used when the geometry already sits flush with the surface
        int waterDepth = fTerrainData[vertex] >> 11 & 0xFFF;
        if (waterDepth > 1) {
            ivec2 cam = ivec2(cameraPos.xz / CHUNK_SIZE) * CHUNK_SIZE + CHUNK_SIZE / 2;
            ivec2 d = ivec2(abs(worldPosition.xz - cam) / TILE_SIZE);
            if (max(d.x, d.y) > int(drawDistance / 8) * 8 + 3)
                worldPosition.y -= waterDepth;
        }

        OUT.position = worldPosition;
        OUT.uv = computeVertexUvs(materialData, worldPosition, vUv.xyz);
        OUT.normal = worldNormal;
        OUT.texBlend = vec3(0);
        OUT.texBlend[vertex] = 1.0;

        #if FLAT_SHADING
            fFlatNormal = worldNormal;
        #endif

        vViewZ = (viewMatrix * vec4(worldPosition, 1.0)).z;

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
