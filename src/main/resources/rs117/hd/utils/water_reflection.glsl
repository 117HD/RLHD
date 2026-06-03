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
#include <uniforms/reflection_planes.glsl>

uniform sampler2DArray waterReflectionMap;

vec3 sampleWaterReflection(vec3 flatR, vec3 R, float distortionFactor) {
    // Only use the reflection map when enabled, the height difference is negligible & the surface is roughly flat
    bool isNormalPlanar = -fFlatNormal.y > .7;
    if (RENDER_PASS == RENDER_PASS_WATER_REFLECTION || PLANAR_REFLECTIONS == 0 || !isNormalPlanar)
        return srgbToLinear(fogColor);
//    // TODO: decide which tris to enable the reflection for in the geometry shader
//    if (renderPass == RENDER_PASS_WATER_REFLECTION ||
//        !waterReflectionEnabled ||
//        abs(IN.position.y - mostPrevalentWaterLevel) > WATER_REFLECTION_HEIGHT_THRESHOLD ||
//        -fFlatNormal.y < .7)
//        return srgbToLinear(fogColor);

    // Find the closest Water plane
    int planeIdx = findClosestPlane(IN.position.y);
    if(planeIdx == -1)
        return srgbToLinear(fogColor);

    ReflectionPlane plane = planes[planeIdx];
    //waterHeight is present as a uniform, it repersents the height for which the planar reflections we're rendered at

    float dist = length(IN.position - sceneCamera.position);
    distortionFactor *= 1 - exp(-dist * .0004);

    // Don't distort too close to the shore
    float shoreLineMask = 1 - dot(IN.texBlend, fAlphaBiasHsl / 127.f);
    distortionFactor *= 1 - shoreLineMask * 1.1; // safety factor to remove artifacts

    // Compute the height difference between this water tile and the plane
    // the reflection was rendered at, then shift the reflected point up by
    // twice that delta (reflection geometry moves 2px per 1 unit of height).
    const float REFLECTION_DEPTH = 20.0;
    float heightDelta = plane.height - IN.position.y;
    vec3 reflectedPos = IN.position + flatR * REFLECTION_DEPTH;
    reflectedPos.y += 2.0 * heightDelta;

    vec2 baseUV = Camera_worldToPixel(plane.camera, reflectedPos);

    // Distortion (same as before)
    vec3 flatRhoriz = flatR * vec3(1, 0, 1);
    vec2 distortion = vec2(0);
    if (dot(flatRhoriz, flatRhoriz) > 0.001) {
        vec3 uvX = normalize(cross(flatRhoriz, flatR));
        vec3 uvY = cross(uvX, flatR);
        float x = dot(R, uvX);
        float y = dot(R, uvY);
        distortion = vec2(x, y) * distortionFactor;
    } else {
        // Near-vertical view: use normal's XZ deviation as distortion
        distortion = (R.xz - flatR.xz) * distortionFactor;
    }

    vec2 uv = (baseUV + distortion) / sceneCamera.viewport;
    vec3 c = texture(waterReflectionMap, vec3(uv, planeIdx)).rgb;

    #if !LINEAR_ALPHA_BLENDING
        // When linear alpha blending is on, the texture is in sRGB, and OpenGL will automatically convert it to linear
        c = srgbToLinear(c);
    #endif

    return c;
}
