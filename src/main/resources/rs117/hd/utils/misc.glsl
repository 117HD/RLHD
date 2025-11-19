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
#include <buffers/model_data.glsl>

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

#if ZONE_RENDERER
bool getDetailCullingFade(ModelData modelData, vec3 sceneOffset, out float detailFade) {
    vec3 modelPos = modelData.position + sceneOffset;
    float modelDist = distance(modelPos, cameraPos);
    float detailDrawDistanceTiles = detailDrawDistance * TILE_SIZE;
    if(isStaticModel(modelData)) {
        detailDrawDistanceTiles *= 2.0;
    }
    if(modelDist > detailDrawDistanceTiles) {
        detailFade = 1.0;
        return false; // Cull model
    }
    float fadeRange = 4.0 * TILE_SIZE;
    float fadeStart = max(0.0, detailDrawDistanceTiles - fadeRange);
    detailFade = clamp((modelDist - fadeStart) / fadeRange, 0.0, 1.0);
    return true;
}

bool getDetailCullingFade(ModelData modelData, vec3 sceneOffset) {
    float fade;
    return getDetailCullingFade(modelData, sceneOffset, fade);
}
#endif

const int DITHER_MAP_LEN = 4;
const float DITHER_MAP_SCALE = 16.0;

const float DITHER_MAP[DITHER_MAP_LEN * DITHER_MAP_LEN] = float[](
     0.0 / DITHER_MAP_SCALE,  8.0 / DITHER_MAP_SCALE,  2.0 / DITHER_MAP_SCALE, 10.0 / DITHER_MAP_SCALE,
    12.0 / DITHER_MAP_SCALE,  4.0 / DITHER_MAP_SCALE, 14.0 / DITHER_MAP_SCALE,  6.0 / DITHER_MAP_SCALE,
     3.0 / DITHER_MAP_SCALE, 11.0 / DITHER_MAP_SCALE,  1.0 / DITHER_MAP_SCALE,  9.0 / DITHER_MAP_SCALE,
    15.0 / DITHER_MAP_SCALE,  7.0 / DITHER_MAP_SCALE, 13.0 / DITHER_MAP_SCALE,  5.0 / DITHER_MAP_SCALE
);

// ------------------------------------------------------------
// Based on https://www.shadertoy.com/view/4t2cRt
// Returns a dither value (0.0 or 1.0) based on coords + opacity
// ------------------------------------------------------------
float orderedDither(vec2 pixelCoord, float opacity, float scaleFactor)
{
    // index into 4×4 matrix
    int i = int(pixelCoord.x / scaleFactor) % DITHER_MAP_LEN;
    int j = int(pixelCoord.y / scaleFactor) % DITHER_MAP_LEN;

    int index = i + j * DITHER_MAP_LEN;

    return (DITHER_MAP[index] < clamp(opacity, 0.0, 1.0)) ? 0.0 : 1.0;
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

// 2D Random
float hash(in vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898,78.233))) * 43758.5453123);
}

// 2D Noise based on Morgan McGuire @morgan3d, under the BSD license
// https://www.shadertoy.com/view/4dS3Wd
float noise(in vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);

    // Four corners in 2D of a tile
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    // Smooth Interpolation

    // Cubic Hermine Curve.  Same as SmoothStep()
    vec2 u = f*f*(3.0-2.0*f);
    // u = smoothstep(0.,1.,f);

    // Mix 4 coorners percentages
    return mix(a, b, u.x) +
        (c - a)* u.y * (1.0 - u.x) +
        (d - b) * u.x * u.y;
}

vec3 snap(vec3 position, float gridSpacing) {
    position /= gridSpacing;
    position = round(position);
    position *= gridSpacing;
    return position;
}

vec2 snap(vec2 position, float gridSpacing) {
    return snap(vec3(position.x, 0.0, position.y), gridSpacing).xz;
}

float smooth_step(float edge0, float edge1, float x) {
    float p = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
    float v = p * p * (3.0 - 2.0 * p); // smoothstep formula
    return v;
}

vec3 safe_normalize(vec3 v) {
    vec3 r = normalize(v);
    r.x = isnan(r.x) ? 0.0 : r.x;
    r.y = isnan(r.y) ? 0.0 : r.y;
    r.z = isnan(r.z) ? 0.0 : r.z;
    return r;
}

float saturate(float value) {
    return clamp(value, 0.0, 1.0);
}

vec2 saturate(vec2 value) {
    return clamp(value, vec2(0.0), vec2(1.0));
}

vec3 saturate(vec3 value) {
    return clamp(value, vec3(0.0), vec3(1.0));
}

vec4 saturate(vec4 value) {
    return clamp(value, vec4(0.0), vec4(1.0));
}
