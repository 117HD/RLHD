/*
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

#include PARALLAX_OCCLUSION_MAPPING

#if PARALLAX_OCCLUSION_MAPPING
float sampleHeight(const Material material, const vec2 uv) {
    return linearToSrgb(texture(textureArray, vec3(uv, material.displacementMap)).r);
}

void sampleDisplacementMap(
    const Material material,
    const vec3 tsViewDir,
    const vec3 tsLightDir,
    inout vec2 uv,
    inout vec3 fragDelta,
    inout float selfShadowing
) {
    if (material.displacementMap == -1)
        return;

    float scale = material.displacementScale;

    // TODO: consider anti-aliasing to fit more nicely with MSAA
    // TODO: improve close-up accuracy
    const float minLayers = 1;
    const float maxLayers = 16;
    float cosView = normalize(tsViewDir).z;
    float numLayers = mix(minLayers, maxLayers, 1 - clamp(cosView * cosView, 0, 1));
    float heightPerLayer = 1. / numLayers;

    vec2 deltaXyPerZ = tsViewDir.xy / tsViewDir.z * scale;

    float height = 0;
    float prevHeight = 0;
    float layer = 1;
    float prevLayer = 1;
    for (; layer >= 0 && height <= layer; layer -= heightPerLayer) {
        prevLayer = layer;
        prevHeight = height;
        height = sampleHeight(material, uv - deltaXyPerZ * layer);
    }

    float overshoot = height - layer;
    float undershoot = (layer + heightPerLayer) - prevHeight;
    height = mix(height, prevHeight, overshoot / (overshoot + undershoot));

    vec3 tsDelta = vec3(deltaXyPerZ, scale) * height;
    uv -= tsDelta.xy;
    fragDelta += tsDelta;

    // TODO: fix self-shadowing at steep angles
//    #undef PARALLAX_OCCLUSION_MAPPING
//    #define PARALLAX_OCCLUSION_MAPPING 2
    #if PARALLAX_OCCLUSION_MAPPING >= 2 // self-shadowing
        float cosLight = normalize(tsLightDir).z;
        float shadowBias = max(.0001, pow(1 - cosLight, 5.) * scale);

        // Prepare for shadow steps
        deltaXyPerZ = tsLightDir.xy / tsLightDir.z * scale;
        layer = height;

        #if PARALLAX_OCCLUSION_MAPPING == 2 // hard shadows
            for (; layer <= 1 && height <= layer; layer += heightPerLayer)
                height = sampleHeight(material, uv - deltaXyPerZ * layer) - shadowBias;

            if (layer <= 1)
                selfShadowing++;
        #else // PCF 3x3 soft shadows
            float shadow = 0;
            float texelSize = 1. / textureSize(textureArray, 0).x;
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    vec2 sUv = uv + (vec2(x, y) - .5) * texelSize;

                    float sLayer = layer;
                    float sHeight = height;
                    for (; sLayer <= 1 && sHeight <= sLayer; sLayer += heightPerLayer)
                        sHeight = sampleHeight(material, sUv - deltaXyPerZ * sLayer);

                    if (sLayer <= 1)
                        shadow++;
                }
            }
            selfShadowing += shadow / 9;
        #endif
    #endif
}
#else
#define sampleDisplacementMap(mat, viewDir, lightDir, fragDelta, selfShadowing) uv
#endif
