#pragma once

/**
From:
https://github.com/mitsuhiko/webgl-meincraft

Copyright (c) 2011 by Armin Ronacher.

Some rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

    * The names of the contributors may not be used to endorse or
      promote products derived from this software without specific
      prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

#if GL_ARB_gpu_shader5
    #define FXAA_TEX_GATHER 1
#endif

#ifndef FXAA_SPAN_MAX
    #define FXAA_SPAN_MAX     4.0
#endif

#ifndef FXAA_REDUCE_MUL
    #define FXAA_REDUCE_MUL   (1.0/FXAA_SPAN_MAX)
#endif

#ifndef FXAA_REDUCE_MIN
    #define FXAA_REDUCE_MIN   (1.0/128.0)
#endif

#define FXAA_LUMA vec3(0.299, 0.587, 0.114)

struct FXAACoords {
#if !FXAA_TEX_GATHER
    vec2 rgbNW;
    vec2 rgbNE;
    vec2 rgbSW;
    vec2 rgbSE;
#endif
    vec2 rgbM;
};

FXAACoords fxaa_coords(vec2 fragCoord, vec2 resolution) {
    vec2 inverseVP = vec2(1.0 / resolution.x, 1.0 / resolution.y);
    FXAACoords coords;
#if !FXAA_TEX_GATHER
    coords.rgbNW = (fragCoord + vec2(-1.0,  1.0)) * inverseVP;
    coords.rgbNE = (fragCoord + vec2( 1.0,  1.0)) * inverseVP;
    coords.rgbSW = (fragCoord + vec2(-1.0, -1.0)) * inverseVP;
    coords.rgbSE = (fragCoord + vec2( 1.0, -1.0)) * inverseVP;
#endif
    coords.rgbM  =  fragCoord                     * inverseVP;
    return coords;
}

vec4 fxaa(sampler2D tex, vec2 fragCoord, vec2 resolution, FXAACoords coords) {
    vec2 inverseVP = vec2(1.0 / resolution.x, 1.0 / resolution.y);
    vec2 snappedM = (floor(fragCoord) + 0.5) * inverseVP;

    #if FXAA_TEX_GATHER
        // Three textureGather calls replace four corner texture2D calls.
        // Each gathers one channel from the 2x2 quad centred on rgbM.
        vec4 gR = textureGather(tex, snappedM, 0);
        vec4 gG = textureGather(tex, snappedM, 1);
        vec4 gB = textureGather(tex, snappedM, 2);

        vec3 rgbNW = vec3(gR.w, gG.w, gB.w);
        vec3 rgbNE = vec3(gR.z, gG.z, gB.z);
        vec3 rgbSW = vec3(gR.x, gG.x, gB.x);
        vec3 rgbSE = vec3(gR.y, gG.y, gB.y);
    #else
        vec3 rgbNW = textureLod(tex, coords.rgbNW, 0.0).xyz;
        vec3 rgbNE = textureLod(tex, coords.rgbNE, 0.0).xyz;
        vec3 rgbSW = textureLod(tex, coords.rgbSW, 0.0).xyz;
        vec3 rgbSE = textureLod(tex, coords.rgbSE, 0.0).xyz;
    #endif

    vec4 texColor = textureLod(tex, snappedM, 0.0);
    float lumaNW  = dot(rgbNW, FXAA_LUMA);
    float lumaNE  = dot(rgbNE, FXAA_LUMA);
    float lumaSW  = dot(rgbSW, FXAA_LUMA);
    float lumaSE  = dot(rgbSE, FXAA_LUMA);
    float lumaM   = dot(texColor.rgb,  FXAA_LUMA);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) *
                          (0.25 * FXAA_REDUCE_MUL), FXAA_REDUCE_MIN);

    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = min(vec2(FXAA_SPAN_MAX, FXAA_SPAN_MAX),
              max(vec2(-FXAA_SPAN_MAX, -FXAA_SPAN_MAX),
              dir * rcpDirMin)) * inverseVP;

    vec3 rgbA = 0.5 * (
        textureLod(tex, fragCoord * inverseVP + dir * (1.0 / 3.0 - 0.5), 0.0).xyz +
        textureLod(tex, fragCoord * inverseVP + dir * (2.0 / 3.0 - 0.5), 0.0).xyz);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
        textureLod(tex, fragCoord * inverseVP + dir * -0.5, 0.0).xyz +
        textureLod(tex, fragCoord * inverseVP + dir * 0.5, 0.0).xyz);

    float lumaB = dot(rgbB, FXAA_LUMA);
    if ((lumaB < lumaMin) || (lumaB > lumaMax))
        return vec4(rgbA, texColor.a);

    return vec4(rgbB, texColor.a);
}