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

uniform int renderPass;
uniform int waterHeight;

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

#include <utils/constants.glsl>
#define USE_VANILLA_UV_PROJECTION
#include <utils/uvs.glsl>
#include <utils/color_utils.glsl>
#include <utils/misc.glsl>

in vec3 gPosition[3];
in vec3 gUv[3];
in vec3 gNormal[3];
in int gAlphaBiasHsl[3];
in int gMaterialData[3];
in int gTerrainData[3];
in int gWorldViewId[3];

flat out int vWorldViewId;
flat out ivec3 vAlphaBiasHsl;
flat out ivec3 vMaterialData;
flat out ivec3 vTerrainData;
flat out vec3 T;
flat out vec3 B;

out FragmentData {
    vec3 position;
    vec2 uv;
    vec3 normal;
    vec3 flatNormal;
    vec3 texBlend;
} OUT;

void displaceUnderwaterPosition(inout vec3 position, int waterDepth) {
    if (waterDepth <= 1 || position.y < waterHeight)
        return;

    // Only displace underwater surfaces viewed from above
    vec3 I = normalize(position - cameraPos);
    if (I.y < .15)
        return;

    // This is quite arbitrary, but a correct solution is non-trivial
    // See https://en.wikipedia.org/wiki/Fermat%27s_principle#/media/File:Fermat_Snellius.svg
    // which boils down to a quartic equation when solving for x given A, B and b
    vec3 refracted = 1.3 * I + vec3(0, .3, 0);
    position += (I - refracted / refracted.y) * waterDepth;
}

void main() {
    float alpha = 1 - float(gAlphaBiasHsl[0] >> 24 & 0xFF) / 0xFF;
    // Hide vertices with barely any opacity, since Jagex often includes hitboxes as part of the model.
    // This prevents them from showing up in planar reflections due to depth testing.
    if (alpha < .004)
        return;

    vWorldViewId = gWorldViewId[0];

    // MacOS doesn't allow assigning these arrays directly.
    // One of the many wonders of Apple software...
    vec3 vUv[3];
    for (int i = 0; i < 3; i++) {
        vAlphaBiasHsl[i] = gAlphaBiasHsl[i];
        vUv[i] = gUv[i];
        vMaterialData[i] = gMaterialData[i];
        vTerrainData[i] = gTerrainData[i];
    }

    int materialData = vMaterialData[0];

    computeUvs(materialData, gWorldViewId[0], vec3[](gPosition[0], gPosition[1], gPosition[2]), vUv);

    // Calculate tangent-space vectors
    mat2 triToUv = mat2(
        vUv[1].xy - vUv[0].xy,
        vUv[2].xy - vUv[0].xy
    );
    if (determinant(triToUv) == 0)
        triToUv = mat2(1);
    mat2 uvToTri = inverse(triToUv) * -1; // Flip UV direction, since OSRS UVs are oriented strangely
    mat2x3 triToWorld = mat2x3(
        gPosition[1] - gPosition[0],
        gPosition[2] - gPosition[0]
    );
    mat2x3 TB = triToWorld * uvToTri; // Preserve scale in order for displacement to interact properly with shadow mapping
    T = TB[0];
    B = TB[1];
    vec3 N = normalize(cross(triToWorld[0], triToWorld[1]));

    // Water data
    bool isTerrain = (vTerrainData[0] & 1) != 0; // 1 = 0b1
    int waterDepth = vTerrainData[0] >> 8 & 0x7FF;
    int waterTypeIndex = 0;
    if (isTerrain) {
        #ifdef DEVELOPMENT_WATER_TYPE
            waterTypeIndex = DEVELOPMENT_WATER_TYPE;
        #else
            waterTypeIndex = vTerrainData[0] >> 3 & 0x1F;
        #endif
    }

    bool isWater = waterTypeIndex > 0;
    bool isUnderwaterTile = waterDepth != 0;
    bool isWaterSurface = isWater && !isUnderwaterTile;

    #if UNDO_VANILLA_SHADING && ZONE_RENDERER
        if ((materialData >> MATERIAL_FLAG_UNDO_VANILLA_SHADING & 1) == 1) {
            for (int i = 0; i < 3; i++) {
                vec3 normal = gNormal[i];
                float magnitude = length(normal);
                if (magnitude == 0) {
                    normal = N;
                } else {
                    normal /= magnitude;
                }
                // TODO: Rotate normal for player shading reversal
                undoVanillaShading(vAlphaBiasHsl[i], normal);
            }
        }
    #endif

    if (renderPass == RENDER_PASS_WATER_REFLECTION) {
        float minY = min(min(gPosition[0].y, gPosition[1].y), gPosition[2].y);

        if (isWater) {
            // Hide flat water surface tiles in the reflection
            bool isFlat = -N.y > .7;
            if (isWaterSurface && isFlat)
                return;

            // Hide underwater tiles from the reflection
            if (isUnderwaterTile && waterHeight - minY <= WATER_REFLECTION_HEIGHT_THRESHOLD)
                return;
        } else {
            // Hide stuff which is under the water from the reflection
            if (waterHeight - minY <= 0)
                return;
        }
    }

    for (int i = 0; i < 3; i++) {
        vec4 pos = vec4(gPosition[i], 1);
        // Flat normals must be applied separately per vertex
        vec3 normal = gNormal[i];

        OUT.position = pos.xyz;
        OUT.uv = vUv[i].xy;
        OUT.flatNormal = N;
        #if FLAT_SHADING
            OUT.normal = N;
        #else
            OUT.normal = length(normal) == 0 ? N : normalize(normal);
        #endif
        OUT.texBlend = vec3(0);
        OUT.texBlend[i] = 1;

        // Apply some arbitrary displacement to mimic refraction
        // TODO: Solve the quartic equation numerically
        int waterDepth = vTerrainData[i] >> 8 & 0x7FF;
//        displaceUnderwaterPosition(position, waterDepth);

        if (renderPass == RENDER_PASS_WATER_REFLECTION && isWaterSurface) {
            // Hide some Z-fighting issues with waterfalls
            pos.xyz += 16 * N * vec3(1, 0, 1);
        }

        pos = projectionMatrix * pos;
        #if ZONE_RENDERER
            int depthBias = (gAlphaBiasHsl[i] >> 16) & 0xff;
            pos.z += depthBias / 128.0;
        #endif
        gl_Position = pos;
        EmitVertex();
    }

    EndPrimitive();
}
