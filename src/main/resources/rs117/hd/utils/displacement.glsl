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

#if PARALLAX_MAPPING
float sampleDepth(int heightMap, vec2 uv) {
    return 1 - linearToSrgb(texture(textureArray, vec3(uv, heightMap)).r);
}

void sampleDisplacementMap(
    Material mat,
    vec3 tsViewDir,
    vec3 tsLightDir,
    inout vec2 uv,
    inout vec2 fragDelta,
    inout float selfShadowing
) {
    int map = mat.displacementMap;
    if (map == -1)
        return;

    // TODO: improve displacement accuracy based on pixels on screen per tangent space unit
    // TODO: improve shadow accuracy
    // TODO: add basic anti-aliasing to fit more nicely with MSAA
    // TODO: fix tile blending issues
    // TODO: fix displacement on vertical surfaces
    // TODO: make the code more readable

    const float minLayers = 4;
    const float maxLayers = 32;
    float numLayers = mix(maxLayers, minLayers, max(0, tsViewDir.z));

    float scale = mat.displacementScale;

    // ts = tangent space
    vec2 deltaXyPerZ = tsViewDir.xy / tsViewDir.z * scale;
    vec2 stepSize = deltaXyPerZ / numLayers;

    float layerSize = 1. / numLayers;
    float layerDepth = 0;
    float depth = sampleDepth(map, uv);
    float prevDepth = depth;
    while (layerDepth < depth) {
        prevDepth = depth;
        uv -= stepSize;
        layerDepth += layerSize;
        depth = sampleDepth(map, uv);
    }

    float after = depth - layerDepth;
    float before = prevDepth - (layerDepth - layerSize);
    float weight = after / (after - before);
    uv += stepSize * weight;
    layerDepth -= layerSize * weight;

    vec2 tsSpaceDelta = -deltaXyPerZ;

    // Prepare for shadow steps
    deltaXyPerZ = tsLightDir.xy / tsLightDir.z * scale;

    tsSpaceDelta += deltaXyPerZ;
    fragDelta += tsSpaceDelta * layerDepth * 128;

    #if PARALLAX_MAPPING >= 2 // self-shadowing
        depth = sampleDepth(map, uv);
        stepSize = deltaXyPerZ / numLayers;
        float shadowDepth;
        float shadow = 0;
        float shadowBias = .0125;

        #if PARALLAX_MAPPING == 2 // hard shadows
            vec2 shadowUv = uv;
            float shadowLayerDepth = depth - shadowBias;
            do {
                shadowUv += stepSize;
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
                        shadowUv += stepSize;
                        shadowLayerDepth -= layerSize;
                        shadowDepth = sampleDepth(map, shadowUv);
                    } while (shadowDepth > shadowLayerDepth && shadowLayerDepth >= 0);
                    if (shadowLayerDepth > 0)
                        shadow++;
                }
            }
            shadow /= 4;
        #endif

        selfShadowing += shadow;
    #endif
}
#else
#define sampleDisplacementMap(mat, viewDir, lightDir, fragDelta, selfShadowing) uv
#endif
