/*
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
#pragma once

#include <uniforms/global.glsl>

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

// translates a value from a custom range into 0-1
float translateRange(float rangeStart, float rangeEnd, float value) {
    return (value - rangeStart) / (rangeEnd - rangeStart);
}

// returns a value between 0-1 representing a frame of animation
// based on the length of the animation
float animationFrame(float animationDuration) {
    if (animationDuration == 0)
        return 0.0;
    return mod(elapsedTime, animationDuration) / animationDuration;
}

vec2 animationFrame(vec2 animationDuration) {
    if (animationDuration == vec2(0))
        return vec2(0);
    return mod(vec2(elapsedTime), vec2(animationDuration)) / animationDuration;
}

vec3 windowsHdrCorrection(vec3 c) {
    // SDR monitors *usually* apply a gamma 2.2 curve, instead of the piece-wise sRGB curve, leading to the following
    // technically incorrect operation for *most* SDR monitors, producing our *expected* final result (first line).
    // In Windows' SDR-in-HDR implementation however, the piece-wise sRGB EOTF is used, leading to technically correct
    // linear colors before transformation to HDR, but this is *not* the *expected* output. To counteract this, we can
    // transform our output from linear to sRGB, then from gamma 2.2 to linear, effectively replacing Windows' HDR sRGB
    // conversion with our expected gamma 2.2 conversion for SDR content, to within rounding error of the output format.
    // sRGB ----------------------------------> SDR screen gammaToLinear --> expected (although technically incorrect)
    // sRGB ----------------------------------> Windows' HDR srgbToLinear -> linear (technically correct, not expected)
    // sRGB -> linearToSrgb -> gammaToLinear -> Windows' HDR srgbToLinear -> expected (same as the SDR case)
    // https://github.com/clshortfuse/renodx (MIT license)
    return pow(linearToSrgb(c), vec3(2.2));
}

void undoVanillaShading(inout int hsl, vec3 unrotatedNormal) {
    const vec3 LIGHT_DIR_MODEL = vec3(0.57735026, 0.57735026, 0.57735026);
    // subtracts the X lowest lightness levels from the formula.
    // helps keep darker colors appropriately dark
    const int IGNORE_LOW_LIGHTNESS = 3;
    // multiplier applied to vertex' lightness value.
    // results in greater lightening of lighter colors
    const float LIGHTNESS_MULTIPLIER = 3.f;
    // the minimum amount by which each color will be lightened
    const int BASE_LIGHTEN = 10;

    int saturation = hsl >> 7 & 0x7;
    int lightness = hsl & 0x7F;
    float vanillaLightDotNormals = dot(LIGHT_DIR_MODEL, unrotatedNormal);
    if (vanillaLightDotNormals > 0) {
        vanillaLightDotNormals /= length(unrotatedNormal);
        float lighten = max(0, lightness - IGNORE_LOW_LIGHTNESS);
        lightness += int((lighten * LIGHTNESS_MULTIPLIER + BASE_LIGHTEN - lightness) * vanillaLightDotNormals);
    }
    int maxLightness;
    #if LEGACY_GREY_COLORS
        maxLightness = 55;
    #else
        maxLightness = int(127 - 72 * pow(saturation / 7., .05));
    #endif
    lightness = min(lightness, maxLightness);
    hsl &= ~0x7F;
    hsl |= lightness;
}
