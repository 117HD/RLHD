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

#include <uniforms/global.glsl>
#include <uniforms/ui.glsl>

uniform sampler2D sceneTexture;
uniform sampler2D uiTexture;

#include <scaling/bicubic.glsl>
#include <utils/constants.glsl>
#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>
#include <utils/fxaa.glsl>

#if UI_SCALING_MODE == UI_SCALING_MODE_XBR
    #include <scaling/xbr_lv2_frag.glsl>

    in XBRTable xbrTable;
#elif UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
    #include <scaling/hybrid.glsl>
#endif

in vec2 fUv;

#if FXAA_ENABLED
    in FXAACoords sceneFxaaCoords;
#endif

out vec4 FragColor;

vec4 alphaBlend(vec4 src, vec4 dst) {
    return vec4(
        src.rgb + dst.rgb * (1.0f - src.a),
        src.a + dst.a * (1.0f - src.a)
    );
}

void main() {
    vec4 uiColor;
    #if UI_SCALING_MODE == UI_SCALING_MODE_MITCHELL || UI_SCALING_MODE == UI_SCALING_MODE_CATROM
        uiColor = textureCubic(uiTexture, fUv);
    #elif UI_SCALING_MODE == UI_SCALING_MODE_XBR
        uiColor = textureXBR(uiTexture, fUv, xbrTable, ceil(1.0 * targetDimensions.x / sourceDimensions.x));
    #elif UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
        uiColor = textureHybrid(uiTexture, fUv);
    #else
        uiColor = texture(uiTexture, fUv);
    #endif

    vec2 scenePixel = vec2(fUv.x, 1.0 - fUv.y) * vec2(targetDimensions);
    vec3 sceneColor = vec3(0.0);
    if (contains(scenePixel, sceneViewport.xy, sceneViewport.xy + sceneViewport.zw)) {
        #if FXAA_ENABLED
            vec2 sceneFragCoord = scenePixel - sceneViewport.xy;
            sceneColor = fxaa(sceneTexture, sceneFragCoord, sceneViewport.zw, sceneFxaaCoords).rgb;
        #else
            vec2 sceneUV = saturate((scenePixel - sceneViewport.xy) / sceneViewport.zw);
            sceneColor = texture(sceneTexture, sceneUV).rgb;
        #endif

        #if WINDOWS_HDR_CORRECTION
            sceneColor = windowsHdrCorrection(sceneColor);
        #endif
    }

    uiColor = alphaBlend(uiColor, alphaOverlay);
    uiColor.rgb = colorBlindnessCompensation(uiColor.rgb);

    #if WINDOWS_HDR_CORRECTION
        uiColor.rgb = windowsHdrCorrection(uiColor.rgb);
    #endif

    FragColor = vec4(mix(sceneColor, uiColor.rgb, uiColor.a), 1.0);
}
