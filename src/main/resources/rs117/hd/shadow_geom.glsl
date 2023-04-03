/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

uniform mat4 lightProjectionMatrix;
uniform float elapsedTime;

#include uniforms/materials.glsl

#include utils/polyfills.glsl
#include utils/constants.glsl
#include utils/misc.glsl
#include utils/uvs.glsl

flat in vec3 gPosition[3];
flat in vec3 gUv[3];
flat in int gMaterialData[3];
flat in int gCastShadow[3];

out vec3 fUvw;

#if SHADOW_TRANSPARENCY
    flat in float gOpacity[3];
    out float fOpacity;
#endif

void main() {
    if (gCastShadow[0] + gCastShadow[1] + gCastShadow[2] == 0)
        return;

    // MacOS doesn't allow assigning these arrays directly.
    // One of the many wonders of Apple software...
    vec3 uvw[3] = vec3[](gUv[0], gUv[1], gUv[2]);
    computeUvs(gMaterialData[0], vec3[](gPosition[0], gPosition[1], gPosition[2]), uvw);

    for (int i = 0; i < 3; i++) {
        Material material = getMaterial(gMaterialData[i] >> MATERIAL_INDEX_SHIFT);
        fUvw = vec3(uvw[i].xy, material.colorMap);
        // Scroll UVs
        fUvw.xy += material.scrollDuration * elapsedTime;
        // Scale from the center
        fUvw.xy = .5 + (fUvw.xy - .5) / material.textureScale;

        #if SHADOW_TRANSPARENCY
            fOpacity = gOpacity[i];
        #endif

        gl_Position = lightProjectionMatrix * vec4(gPosition[i], 1);
        EmitVertex();
    }
    EndPrimitive();
}
