#pragma once

#include <uniforms/ui.glsl>

#if UI_SCALING_MODE == UI_SCALING_MODE_MITCHELL || UI_SCALING_MODE == UI_SCALING_MODE_CATROM
    #include <scaling/bicubic.glsl>
#elif UI_SCALING_MODE == UI_SCALING_MODE_XBR
    #include <scaling/xbr_lv2_vert.glsl>
    #include <scaling/xbr_lv2_frag.glsl>
#elif UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
    #include <scaling/hybrid.glsl>
#endif

// Samples the UI as it's stretched onto the screen. Native item icons, drawn beneath the UI, use it to know what the UI lets through.
vec4 textureUi(sampler2D tex, vec2 uv) {
    #if UI_SCALING_MODE == UI_SCALING_MODE_MITCHELL || UI_SCALING_MODE == UI_SCALING_MODE_CATROM
        return textureCubic(tex, uv);
    #elif UI_SCALING_MODE == UI_SCALING_MODE_XBR
        return textureXBR(tex, uv, xbr_vert(uv, uboUi.sourceDimensions), ceil(1.0 * uboUi.targetDimensions.x / uboUi.sourceDimensions.x));
    #elif UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
        return textureHybrid(tex, uv);
    #else // NEAREST or LINEAR, which uses GL_TEXTURE_MIN_FILTER/GL_TEXTURE_MAG_FILTER to affect sampling
        return texture(tex, uv);
    #endif
}
