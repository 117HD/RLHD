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

#if SHADER_TYPE == FRAGMENT_SHADER
    mat3 cotangent_frame(vec3 N, vec3 p, vec2 uv) {
        // get edge vectors of the pixel triangle
        vec3 dpdx = dFdx(p);
        vec3 dpdy = dFdy(p);
        vec2 duvdx = dFdx(uv);
        vec2 duvdy = dFdy(uv);

        // solve the linear system
        vec3 a = cross(dpdy, N);
        vec3 b = cross(N, dpdx);
        vec3 T = a * duvdx.x + b * duvdy.x;
        vec3 B = a * duvdx.y + b * duvdy.y;

        // construct a scale-invariant frame
        float invmax = inversesqrt(max(dot(T, T), dot(B, B)));
        return mat3(T * invmax, B * invmax, N);
    }
#endif

// 2D Random
float hash(in vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

// 3D Random
float hash(in vec3 st) {
	return fract(sin(dot(st, vec3(12.9898, 78.233, 45.164))) * 43758.5453123);
}

// 4D Random
float hash(in vec4 st) {
	return fract(sin(dot(st, vec4(12.9898, 78.233, 45.164, 94.673))) * 43758.5453123);
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

    // Smooth interpolation
    vec2 u = smoothstep(0., 1., f);

    // Mix 4 corner percentages
    return mix(a, b, u.x) +
        (c - a) * u.y * (1.0 - u.x) +
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

#define POISSON_DISK_LENGTH 16
vec2 getPoissonDisk(int idx) {
    switch(idx) {
        case  0: return vec2(-0.94201624,  -0.39906216);
        case  1: return vec2( 0.94558609,  -0.76890725);
        case  2: return vec2(-0.094184101, -0.92938870);
        case  3: return vec2( 0.34495938,   0.29387760);
        case  4: return vec2(-0.91588581,   0.45771432);
        case  5: return vec2(-0.81544232,  -0.87912464);
        case  6: return vec2(-0.38277543,   0.27676845);
        case  7: return vec2( 0.97484398,   0.75648379);
        case  8: return vec2( 0.44323325,  -0.97511554);
        case  9: return vec2( 0.53742981,  -0.47373420);
        case 10: return vec2(-0.26496911,  -0.41893023);
        case 11: return vec2( 0.79197514,   0.19090188);
        case 12: return vec2(-0.24188840,   0.99706507);
        case 13: return vec2(-0.81409955,   0.91437590);
        case 14: return vec2( 0.19984126,   0.78641367);
        default: return vec2( 0.14383161,  -0.14100790);
    }
}

/* Generated with:
import scipy, numpy as np
n = 64
points = scipy.stats.qmc.PoissonDisk(d=2, radius=0.8/np.sqrt(n), seed=117).random(n=64)
disk = scipy.stats.qmc.scale(points, l_bounds=[-1, -1], u_bounds=[1, 1])
print("\n".join(map(
    lambda x: (
        f"case {x[0]:>2}:" if x[0] + 1 < n else "default:") +
        f" return vec2({x[1][0]:> 1.9f}, {x[1][1]:> 1.9f});",
    enumerate(disk))))
*/
vec2 getPoissonDisk64(int idx) {
    switch(idx) {
        case  0: return vec2( 0.750023520, -0.684238029);
        case  1: return vec2( 0.502976671, -0.402302974);
        case  2: return vec2( 0.657981790, -0.985169560);
        case  3: return vec2( 0.451917650, -0.839056464);
        case  4: return vec2( 0.871047563, -0.982620849);
        case  5: return vec2( 0.860952370, -0.421628069);
        case  6: return vec2( 0.207114298, -0.966633837);
        case  7: return vec2( 0.283295401, -0.704024010);
        case  8: return vec2( 0.529814174, -0.636279157);
        case  9: return vec2( 0.284758737, -0.480536754);
        case 10: return vec2(-0.047109168, -0.801857327);
        case 11: return vec2( 0.122156062, -0.342263665);
        case 12: return vec2(-0.075134247, -0.580918221);
        case 13: return vec2( 0.204903033, -0.093351366);
        case 14: return vec2( 0.432993958, -0.148624436);
        case 15: return vec2(-0.341272520, -0.695768319);
        case 16: return vec2(-0.312829951, -0.984865292);
        case 17: return vec2( 0.748542624, -0.207737911);
        case 18: return vec2( 0.452800643,  0.209270410);
        case 19: return vec2( 0.224176865,  0.105986207);
        case 20: return vec2( 0.678823972,  0.035559801);
        case 21: return vec2(-0.547376053, -0.735054697);
        case 22: return vec2(-0.585164720, -0.401083329);
        case 23: return vec2(-0.280773851, -0.345509140);
        case 24: return vec2(-0.553444946, -0.948385812);
        case 25: return vec2( 0.818683781,  0.217183851);
        case 26: return vec2( 0.625954527,  0.371876191);
        case 27: return vec2( 0.962270050,  0.024191663);
        case 28: return vec2(-0.121725806, -0.219097934);
        case 29: return vec2(-0.002301573, -0.054053913);
        case 30: return vec2(-0.352705696, -0.084864470);
        case 31: return vec2(-0.165971283,  0.148359037);
        case 32: return vec2( 0.236102170,  0.337871720);
        case 33: return vec2( 0.509084558,  0.577361089);
        case 34: return vec2(-0.023721081,  0.312303503);
        case 35: return vec2( 0.996882955, -0.241132325);
        case 36: return vec2( 0.983567136, -0.586471948);
        case 37: return vec2(-0.933542166, -0.947131069);
        case 38: return vec2(-0.707136638, -0.580145134);
        case 39: return vec2( 0.848993283,  0.551424218);
        case 40: return vec2(-0.989169512, -0.591812515);
        case 41: return vec2(-0.902890269, -0.272472568);
        case 42: return vec2( 0.757516248,  0.855961707);
        case 43: return vec2( 0.990627838,  0.764846370);
        case 44: return vec2(-0.605999313, -0.137490003);
        case 45: return vec2(-0.938425078,  0.052428479);
        case 46: return vec2(-0.842864800,  0.428388500);
        case 47: return vec2(-0.610393122,  0.199388147);
        case 48: return vec2( 0.050308977,  0.616673055);
        case 49: return vec2(-0.305092171,  0.332995872);
        case 50: return vec2(-0.255095509,  0.637569782);
        case 51: return vec2( 0.544467930,  0.832747637);
        case 52: return vec2( 0.258671150,  0.695545656);
        case 53: return vec2( 0.231880166,  0.915024892);
        case 54: return vec2( 0.003748166,  0.856037391);
        case 55: return vec2(-0.223969842,  0.869012100);
        case 56: return vec2(-0.835244720, -0.743108266);
        case 57: return vec2(-0.647520250,  0.586271079);
        case 58: return vec2(-0.485123936,  0.452107199);
        case 59: return vec2(-0.466031912,  0.998685890);
        case 60: return vec2(-0.637934462,  0.793457357);
        case 61: return vec2(-0.843401269,  0.774693320);
        case 62: return vec2(-0.444208129,  0.705139059);
        default: return vec2(-0.947311349,  0.961819476);
    }
}
