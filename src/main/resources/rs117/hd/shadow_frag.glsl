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

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

#if SHADOW_MODE == SHADOW_MODE_DETAILED
    uniform sampler2DArray textureArray;
    in vec4 fUvw;
    flat in int fMaterialData;
#endif

#if SHADOW_TRANSPARENCY
    uniform sampler2D shadowMap;
    in float fOpacity;

    #if SHADOW_TRANSPARENCY == SHADOW_TRANSPARENCY_ENABLED_WITH_TINT
    in vec3 fColor;
    layout (location = 0) out ivec2 depthAlphaColorOutput;
    #else
    layout (location = 0) out int depthAlphaOutput;
    #endif

    #extension GL_ARB_conservative_depth : enable
    layout (depth_less) out float gl_FragDepth;
#endif

#if ZONE_RENDERER && GROUND_SHADOWS
    in vec3 fFragPos;
    in float fGroundPlane;
    layout (location = 1) out int groundMaskOutput;
#endif

void main() {
    float opacity = 0;
    #if SHADOW_TRANSPARENCY
        opacity = fOpacity;
        #if SHADOW_TRANSPARENCY == SHADOW_TRANSPARENCY_ENABLED_WITH_TINT
            vec3 shadowTint = fColor * fOpacity;
        #endif
    #endif

    #if SHADOW_MODE == SHADOW_MODE_DETAILED
        if (fUvw.z != -1) {
            vec4 uvw = fUvw;

            // Vanilla tree textures rely on UVs being clamped horizontally,
            // which HD doesn't do, so we instead opt to hide these fragments
            if ((fMaterialData >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1)
                uvw.x = clamp(uvw.x, 0, .984375);

            #if SHADOW_TRANSPARENCY
                #if SHADOW_TRANSPARENCY == SHADOW_TRANSPARENCY_ENABLED_WITH_TINT
                    vec4 albedo = texture(textureArray, uvw.xyz);
                    shadowTint = albedo.rgb;
                    opacity = albedo.a;
                #else
                    opacity = texture(textureArray, uvw.xyz).a;
                #endif
                if(uvw.w > 0)
                    opacity *= texture(textureArray, uvw.xyw).a;
                opacity *= fOpacity;
            #else
                opacity = texture(textureArray, uvw.xyz).a;
                if (opacity < SHADOW_DEFAULT_OPACITY_THRESHOLD)
                    discard;
            #endif
        }
    #endif


#if ZONE_RENDERER && GROUND_SHADOWS
    if(fGroundPlane > 0.0) {
        int tileExX = int(fFragPos.x / 128.0) % 255;
        int tileExY = int(fFragPos.z / 128.0) % 255;
        groundMaskOutput = tileExX | tileExY << 8;
    } else {
        groundMaskOutput = 0;
    }
#endif

    #if SHADOW_TRANSPARENCY
    if(opacity != 1.0) {
        float depth = gl_FragCoord.z;
        #if ZONE_RENDERER
        depth -= 0.0015;
        #else
        depth += 0.002;
        #endif
        int alphaDepth =
            int(opacity * SHADOW_ALPHA_MAX) << SHADOW_DEPTH_BITS |
            int(depth * SHADOW_DEPTH_MAX);

        #if SHADOW_TRANSPARENCY == SHADOW_TRANSPARENCY_ENABLED_WITH_TINT
            depthAlphaColorOutput = ivec2(alphaDepth, srgbToPackedHsl(shadowTint));
        #else
            depthAlphaOutput = alphaDepth;
        #endif
        gl_FragDepth = texelFetch(shadowMap, ivec2(gl_FragCoord.xy), 0).r;
    } else {
        gl_FragDepth = gl_FragCoord.z;
    }
    #endif
}
