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

#include utils/constants.glsl

float sampleShadowMap(vec3 fragPos, int waterTypeIndex, vec2 distortion, float lightDotNormals) {
    // sample shadow map
    float shadow = 0.0;
    if (shadowsEnabled == 1)
    {
        vec4 shadowPos = lightProjectionMatrix * vec4(fragPos, 1);
        vec3 projCoords = shadowPos.xyz / shadowPos.w * 0.5 + 0.5;
        projCoords.xy += distortion;

        float currentDepth = projCoords.z;
        float shadowMinBias = 0.0009f;
        float shadowBias = max(shadowMaxBias * (1.0 - lightDotNormals), shadowMinBias);
        vec2 texelSize = 1.0 / textureSize(shadowMap, 0);
        for(int x = -1; x <= 1; ++x)
        {
            for(int y = -1; y <= 1; ++y)
            {
                float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
                shadow += currentDepth - shadowBias > pcfDepth ? 1.0 : 0.0;
            }
        }
        shadow /= 9;

        // fade out shadows near shadow texture edges
        float cutoff = 0.1;
        if (projCoords.x <= cutoff)
        {
            float amt = projCoords.x / cutoff;
            shadow = mix(0.0, shadow, amt);
        }
        if (projCoords.y <= cutoff)
        {
            float amt = projCoords.y / cutoff;
            shadow = mix(0.0, shadow, amt);
        }
        if (projCoords.x >= 1.0 - cutoff)
        {
            float amt = 1.0 - ((projCoords.x - (1.0 - cutoff)) / cutoff);
            shadow = mix(0.0, shadow, amt);
        }
        if (projCoords.y >= 1.0 - cutoff)
        {
            float amt = 1.0 - ((projCoords.y - (1.0 - cutoff)) / cutoff);
            shadow = mix(0.0, shadow, amt);
        }

        shadow = clamp(shadow, 0.0, 1.0);
        shadow = projCoords.z > 1.0 ? 0.0 : shadow;
    }

    return shadow;
}
