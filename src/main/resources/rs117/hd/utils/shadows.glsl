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

#if SHADOW_MODE != SHADOW_MODE_OFF
float fetchShadowTexel(vec2 uv, float fragDepth) {
    #if SHADOW_TRANSPARENCY
        int alphaDepth = int(texelFetch(shadowMap, ivec2(uv), 0).r * SHADOW_COMBINED_MAX);
        float depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
        float alpha = 1 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
        return depth < fragDepth ? alpha : 0;
    #else
        return texelFetch(shadowMap, ivec2(uv), 0).r < fragDepth ? 1 : 0;
    #endif
}

float sampleShadowMap(vec3 fragPos, int waterTypeIndex, vec2 distortion, float lightDotNormals) {
    vec4 shadowPos = lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    // Fade out shadows near shadow texture edges
    float fadeOut = smoothstep(.75, 1., dot(shadowPos.xy, shadowPos.xy));
    if (fadeOut >= 1)
        return 0.f;

    // NDC to texture space
    shadowPos.xyz += 1;
    shadowPos.xyz /= 2;
    shadowPos.xy += distortion;
    shadowPos = clamp(shadowPos, 0, 1);

    vec2 shadowRes = textureSize(shadowMap, 0);
    float shadowMinBias = 0.0002f;
    float shadowBias = shadowMinBias * max(1, (1.0 - lightDotNormals));
    float fragDepth = shadowPos.z - shadowBias;
    float shadow = 0;

    const int kernelSize = 2;
    const float kernelRadius = kernelSize / 2.;
    const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);

    vec2 kernelOffset = shadowPos.xy * shadowRes - kernelRadius + .5;
    vec2 lerp = fract(kernelOffset - .5);
    for (int x = 0; x < kernelSize; ++x) {
        for (int y = 0; y < kernelSize; ++y) {
            shadow += mix(
                mix(
                    fetchShadowTexel(kernelOffset + vec2(x - .5, y - .5), fragDepth),
                    fetchShadowTexel(kernelOffset + vec2(x + .5, y - .5), fragDepth),
                    lerp.x
                ),
                mix(
                    fetchShadowTexel(kernelOffset + vec2(x - .5, y + .5), fragDepth),
                    fetchShadowTexel(kernelOffset + vec2(x + .5, y + .5), fragDepth),
                    lerp.x
                ),
                lerp.y
            );
        }
    }
    shadow *= kernelAreaReciprocal;

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, waterTypeIndex, distortion, lightDotNormals) 0
#endif
