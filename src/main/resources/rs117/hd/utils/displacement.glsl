/*
 * Copyright (c) 2022, Hooder <ahooder@protonmail.com>
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

#include PARALLAX_MAPPING

float sampleDepth(int heightMap, vec2 uv) {
    return 1 - linearToSrgb(texture(textureArray, vec3(uv, heightMap)).r);
}

#if PARALLAX_MAPPING
vec2 sampleDisplacementMap(
    Material mat,
    vec2 uv,
    vec3 tangentViewDir,
    vec3 tangentLightDir,
    inout float selfShadowing,
    inout vec3 fragPos
) {
    int map = mat.displacementMap;
    if (map == -1)
        return uv;

    const float strength = 3;
    const float minLayers = 4 * strength;
    const float maxLayers = 32 * strength;
    float numLayers = mix(maxLayers, minLayers, max(dot(vec3(0, 0, 1), tangentViewDir), 0));

    float scale = mat.displacementScale * strength;
    vec2 P = tangentViewDir.xy / tangentViewDir.z * scale;
    vec2 deltaUv = P / numLayers;

    float layerSize = 1. / numLayers;
    float layerDepth = 0;
    float depth = sampleDepth(map, uv);
    float prevDepth = depth;
    while (layerDepth < depth) {
        prevDepth = depth;
        uv -= deltaUv;
        layerDepth += layerSize;
        depth = sampleDepth(map, uv);
    }

    float after = depth - layerDepth;
    float before = prevDepth - (layerDepth - layerSize);
    float weight = after / (after - before);
    uv += deltaUv * weight;

    fragPos -= TBN * vec3(P, 1) * layerDepth * 128 * scale;
    P = tangentLightDir.xy / tangentLightDir.z * scale;
    fragPos += TBN * vec3(P, 1) * layerDepth * 128 * scale;

    #if PARALLAX_MAPPING >= 2 // self-shadowing
        depth = sampleDepth(map, uv);
        deltaUv = P / numLayers;
        float shadowDepth;
        float shadow = 0;
        float shadowBias = .0125;

        #if PARALLAX_MAPPING == 2 // hard shadows
            vec2 shadowUv = uv;
            float shadowLayerDepth = depth - shadowBias;
            do {
                shadowUv += deltaUv;
                shadowLayerDepth -= layerSize;
                shadowDepth = sampleDepth(map, shadowUv);
            } while (shadowDepth > shadowLayerDepth && shadowLayerDepth >= 0);
            if (shadowLayerDepth > 0)
                shadow++;
        #else // PCF 3x3 soft shadows
            for (int x = 0; x <= 1; x++) {
                for (int y = 0; y <= 1; y++) {
                    vec2 shadowUv = uv + (vec2(x, y) - .5) * .0125;
                    float shadowLayerDepth = depth - shadowBias * 5;
                    do {
                        shadowUv += deltaUv;
                        shadowLayerDepth -= layerSize;
                        shadowDepth = sampleDepth(map, shadowUv);
                    } while (shadowDepth > shadowLayerDepth && shadowLayerDepth >= 0);
                    if (shadowLayerDepth > 0)
                        shadow++;
                }
            }
            shadow /= 4;
        #endif

        selfShadowing = max(selfShadowing, shadow);
    #endif
    return uv;
}
#else
#define sampleDisplacementMap(mat, uv, view, light, selfShadowing, fragPos) uv
#endif
