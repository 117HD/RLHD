/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
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

#include utils/constants.glsl

#if SHADOW_MODE != SHADOW_MODE_OFF
float sampleShadowMap(vec3 fragPos, int waterTypeIndex, vec2 distortion, float lightDotNormals) {
    vec4 shadowPos = lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos = (shadowPos / shadowPos.w) * .5 + .5;
    shadowPos.xy += distortion;
    shadowPos = clamp(shadowPos, 0, 1);

    // Fade out shadows near shadow texture edges
    vec2 uv = shadowPos.xy * 2 - 1;
    float fadeOut = smoothstep(.75, 1, dot(uv, uv));

    if (fadeOut >= 1)
        return 0.f;

    vec2 shadowRes = textureSize(shadowMap, 0);
    float shadowMinBias = 0.0009f;
    float shadowBias = shadowMinBias * max(1, (1.0 - lightDotNormals));
    float fragDepth = shadowPos.z - shadowBias;
    float shadow = 0;

    const int kernelSize = 3;
    const float kernelRadius = kernelSize / 2.;
    const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);

    ivec2 texelOffset = ivec2(shadowPos.xy * shadowRes - kernelRadius + .5);
    #define fetchShadowTexel(x, y) texelFetch(shadowMap, texelOffset + ivec2(x, y), 0).r

    float depth, alpha;
    for (int x = 0; x < kernelSize; ++x) {
        for (int y = 0; y < kernelSize; ++y) {
            #if SHADOW_TRANSPARENCY
                int alphaDepth = int(fetchShadowTexel(x, y) * SHADOW_COMBINED_MAX);
                depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
                alpha = 1 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
            #else
                depth = fetchShadowTexel(x, y);
                alpha = 1;
            #endif

            if (fragDepth > depth)
                shadow += alpha;
        }
    }
    shadow *= kernelAreaReciprocal;

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, waterTypeIndex, distortion, lightDotNormals) 0
#endif
