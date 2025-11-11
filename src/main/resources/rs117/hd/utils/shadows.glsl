/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
#include <uniforms/global.glsl>

#include <utils/constants.glsl>

#include SHADOW_CONSTANT_BIAS
#include SHADOW_SLOPE_BIAS

#if SHADOW_MODE != SHADOW_MODE_OFF
float fetchShadowTexel(ivec2 uv, float fragDepth) {
    #if SHADOW_TRANSPARENCY
        int alphaDepth = int(texelFetch(shadowMap, uv, 0).r * SHADOW_COMBINED_MAX);
        float depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
        float alpha = float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
#if ZONE_RENDERER
        return depth > fragDepth ? alpha : 0.f;
#else
        return depth < fragDepth ? (1.0 - alpha) : 0.f;
#endif
    #else
#if ZONE_RENDERER
        return texelFetch(shadowMap, uv, 0).r > fragDepth ? 1.f : 0.f;
#else
        return texelFetch(shadowMap, uv, 0).r < fragDepth ? 1.f : 0.f;
#endif
    #endif
}

float sampleShadowMap(vec3 fragPos, vec2 distortion, float lightDotNormals) {
    vec4 shadowPos = directionalCamera.viewProj * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    float fadeOut = 0.0;
#if ZONE_RENDERER
    vec4 camShadowPos = directionalCamera.viewProj * vec4(sceneCamera.position, 1);
    camShadowPos.xyz /= camShadowPos.w;

    float camShadowFade = length(shadowPos - camShadowPos);
    // Fade out shadows in the distance
    fadeOut = clamp((camShadowFade - 0.95) / 0.05, 0.0, 1.0);
#else
    // Fade out shadows near shadow texture edges
    fadeOut = smoothstep(.75, 1., dot(shadowPos.xy, shadowPos.xy));
#endif
    if (fadeOut >= 1)
        return 0.f;

    // NDC to texture space
    ivec2 shadowRes = textureSize(shadowMap, 0);
    #if ZONE_RENDERER
    shadowPos.xy += 1; // Zone Renderer uses 0 -> +1
    shadowPos.xy /= 2;
    #else
    shadowPos.xyz += 1; // Legacy uses -1 -> +1
    shadowPos.xyz /= 2;
    #endif
    shadowPos.xy += distortion;
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    shadowPos.xy *= shadowRes;
    shadowPos.xy += .5; // Shift to texel center

    #if ZONE_RENDERER
    vec2 texelSize = 1.0 / vec2(shadowRes);

    vec3 shadowTexDDX = dFdx(shadowPos.xyz);
    vec3 shadowTexDDY = dFdy(shadowPos.xyz);

    shadowTexDDX.xy *= texelSize.x;
    shadowTexDDY.xy *= texelSize.y;

    float receiverBias = abs(dot(shadowTexDDX, vec3(1.0))) + abs(dot(shadowTexDDY, vec3(1.0)));
    float slopeBias = (1.0 - lightDotNormals) * SHADOW_SLOPE_BIAS;

    float shadowBias = SHADOW_CONSTANT_BIAS + slopeBias + receiverBias;
    float fragDepth = shadowPos.z + shadowBias;
    #else
    float shadowMinBias = -0.0009f;
    float shadowBias = shadowMinBias * max(1, (1.0 - lightDotNormals));
    float fragDepth = shadowPos.z + shadowBias;
    #endif

    const int kernelSize = 3;
    ivec2 kernelOffset = ivec2(shadowPos.xy - kernelSize / 2);
    #if PIXELATED_SHADOWS
        const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);
    #else
        const float kernelAreaReciprocal = .25; // This is effectively a 2x2 kernel
        vec2 lerp = fract(shadowPos.xy);
        vec3 lerpX = vec3(1 - lerp.x, 1, lerp.x);
        vec3 lerpY = vec3(1 - lerp.y, 1, lerp.y);
    #endif
    float shadow = 0;
    for (int x = 0; x < kernelSize; ++x) {
        for (int y = 0; y < kernelSize; ++y) {
            #if PIXELATED_SHADOWS
                shadow += fetchShadowTexel(kernelOffset + ivec2(x, y), fragDepth);
            #else
                shadow += fetchShadowTexel(kernelOffset + ivec2(x, y), fragDepth) * lerpX[x] * lerpY[y];
            #endif
        }
    }
    shadow *= kernelAreaReciprocal;

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, distortion, lightDotNormals) 0
#endif
