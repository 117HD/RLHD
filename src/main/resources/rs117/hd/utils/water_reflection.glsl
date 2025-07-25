/*
 * Copyright (c) 2024, Hooder <ahooder@protonmail.com>
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
#include <utils/constants.glsl>

vec3 sampleWaterReflection(vec3 flatR, vec3 R, float distortionFactor) {
    // Only use the reflection map when enabled, the height difference is negligible & the surface is roughly flat
    // TODO: decide which tris to enable the reflection for in the geometry shader
    if (renderPass == RENDER_PASS_WATER_REFLECTION ||
        !waterReflectionEnabled ||
        abs(IN.position.y - waterHeight) > WATER_REFLECTION_HEIGHT_THRESHOLD ||
        -IN.flatNormal.y < .7)
        return srgbToLinear(fogColor);

    float dist = length(IN.position - cameraPos);
    distortionFactor *= 1 - exp(-dist * .0004);

    // Don't distort too close to the shore
    float shoreLineMask = 1 - dot(IN.texBlend, vHsl / 127.f);
    distortionFactor *= 1 - shoreLineMask * 1.1; // safety factor to remove artifacts

    vec3 uvX = normalize(cross(flatR * vec3(1, 0, 1), flatR));
    vec3 uvY = cross(uvX, flatR);
    float x = dot(R, uvX);
    float y = dot(R, uvY);
    vec2 distortion = vec2(x, y) * distortionFactor;

    vec2 uv = gl_FragCoord.xy + distortion;
    uv -= viewport.xy;
    uv /= viewport.zw;

    // This will be linear or sRGB depending on the linear alpha blending setting
    vec3 c = texture(waterReflectionMap, uv).rgb;

    #if !LINEAR_ALPHA_BLENDING
    // When linear alpha blending is on, the texture is in sRGB, and OpenGL will automatically convert it to linear
    c = srgbToLinear(c);
    #endif
    return c;
}
