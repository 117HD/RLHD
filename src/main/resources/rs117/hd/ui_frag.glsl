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

uniform sampler2D uiTexture;

#include <scaling/bicubic.glsl>
#include <utils/constants.glsl>
#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>

#if UI_SCALING_MODE == UI_SCALING_MODE_XBR
    #include <scaling/xbr_lv2_frag.glsl>

    in XBRTable xbrTable;
#elif UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
    #include <scaling/hybrid.glsl>
#endif

in vec2 fUv;

out vec4 FragColor;

// Magic placeholder color MinimapPass's tile-drawer hook paints over real terrain pixels in HD minimap
// mode, so it can be told apart here from the marker/dot/flag/compass pixels vanilla draws on top of it.
// Vanilla also draws wall/door boundary lines on the minimap outside of the tile-drawer callback, in a
// couple of fixed colors, so those are stripped out here too, the same way as the placeholder color.
#define MINIMAP_PLACEHOLDER_COLOR_PACKED 12345678
#define MINIMAP_WALL_COLOR_PACKED 0xF1E6F3
#define MINIMAP_DOOR_COLOR_PACKED 0xEC0000
#define MINIMAP_OBJECT_COLOR_PACKED 0xEEEEEE

#define UNPACK_RGB(packed) ivec3(packed >> 16 & 0xFF, packed >> 8 & 0xFF, packed & 0xFF)

const ivec3 MINIMAP_PLACEHOLDER_COLOR = UNPACK_RGB(MINIMAP_PLACEHOLDER_COLOR_PACKED);
const ivec3 MINIMAP_WALL_COLOR = UNPACK_RGB(MINIMAP_WALL_COLOR_PACKED);
const ivec3 MINIMAP_DOOR_COLOR = UNPACK_RGB(MINIMAP_DOOR_COLOR_PACKED);
const ivec3 MINIMAP_OBJECT_COLOR = UNPACK_RGB(MINIMAP_OBJECT_COLOR_PACKED);

vec4 alphaBlend(vec4 src, vec4 dst) {
    return vec4(
        src.rgb + dst.rgb * (1.0f - src.a),
        src.a + dst.a * (1.0f - src.a)
    );
}

void main() {
    vec4 c;
    #if UI_SCALING_MODE == UI_SCALING_MODE_MITCHELL || UI_SCALING_MODE == UI_SCALING_MODE_CATROM
        c = textureCubic(uiTexture, fUv);
    #elif UI_SCALING_MODE == UI_SCALING_MODE_XBR
        c = textureXBR(uiTexture, fUv, xbrTable, ceil(1.0 * targetDimensions.x / sourceDimensions.x));
    #elif UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
        c = textureHybrid(uiTexture, fUv);
    #else // NEAREST or LINEAR, which uses GL_TEXTURE_MIN_FILTER/GL_TEXTURE_MAG_FILTER to affect sampling
        c = texture(uiTexture, fUv);
    #endif

    c = alphaBlend(c, alphaOverlay);
    c.rgb = colorBlindnessCompensation(c.rgb);

    #if WINDOWS_HDR_CORRECTION
        c.rgb = windowsHdrCorrection(c.rgb);
    #endif

    ivec2 fragCoord = ivec2(gl_FragCoord.xy);
    if (hdMinimapActive &&
        fragCoord.x >= minimapViewport.x && fragCoord.x < minimapViewport.x + minimapViewport.z &&
        fragCoord.y >= minimapViewport.y && fragCoord.y < minimapViewport.y + minimapViewport.w
    ) {
        // c is premultiplied, so un-premultiply before comparing against known colors to strip
        vec3 unpremultiplied = c.a > 0 ? c.rgb / c.a : c.rgb;
        ivec3 rgb255 = ivec3(round(unpremultiplied * 255.0));
        if (rgb255 == MINIMAP_PLACEHOLDER_COLOR ||
            rgb255 == MINIMAP_WALL_COLOR ||
            rgb255 == MINIMAP_DOOR_COLOR ||
            rgb255 == MINIMAP_OBJECT_COLOR
        )
            c = vec4(0);
    }

    FragColor = c;
}
