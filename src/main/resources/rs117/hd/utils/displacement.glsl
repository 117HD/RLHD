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

#include <uniforms/global.glsl>
#include <uniforms/materials.glsl>

#if PARALLAX_OCCLUSION_MAPPING

float sampleHeight(const Material material, const vec2 uv) {
    return linearToSrgb(texture(textureArray, vec3(uv, material.displacementMap)).r);
}

vec3 sampleDisplacementMap(
    inout vec2 uv,
    const Material material,
    mat2 uvGradient,
    mat2x3 worldGradient,
    mat2x3 normalGradient,
    vec3 flatNormal,
    vec3 smoothNormal,
    vec3 viewDir
) {
    if (material.displacementMap == -1)
        return vec3(0);

    float visibility = smoothstep(.005, .02, dot(flatNormal, viewDir));
    if (visibility <= 0 || abs(determinant(uvGradient)) <= 1e-12)
        return vec3(0);

    // Align the approximate average height with the actual surface depth
    float averageHeight = linearToSrgb(textureLod(textureArray,
        vec3(.5, .5, material.displacementMap), 1000.0).r);

    // Calculate TBN from tangent space to world space
    mat2 uvToScreen = inverse(uvGradient);
    mat2x3 worldTangents = -1.0 * (worldGradient * uvToScreen);
    float worldScale = sqrt(length(cross(worldTangents[0], worldTangents[1])));
    if (worldScale <= 1e-6)
        return vec3(0);
    mat3 TBN = mat3(
        worldTangents[0] / worldScale,
        worldTangents[1] / worldScale,
        flatNormal
    );

    float displacementScale = material.displacementScale;

    // Estimate a TBN following the smooth surface normal
    float heightEstimate = displacementScale * (sampleHeight(material, uv) - averageHeight);
    mat2x3 normalTangents = -1 * normalGradient * uvToScreen;
    mat3 smoothTBN = mat3(
        TBN[0] + heightEstimate * normalTangents[0],
        TBN[1] + heightEstimate * normalTangents[1],
        smoothNormal
    );
    vec3 smoothPlane = cross(smoothTBN[0], smoothTBN[1]);
    vec3 basePlane = cross(TBN[0], TBN[1]);
    float orientation = sign(dot(basePlane, flatNormal));
    float curvatureWeight =
        smoothstep(.1, .3, dot(smoothPlane, flatNormal) * orientation) *
        smoothstep(.1, .3, dot(smoothPlane, smoothNormal) * orientation);
    mat3 blendedTBN = mat3(
        mix(TBN[0], smoothTBN[0], curvatureWeight),
        mix(TBN[1], smoothTBN[1], curvatureWeight),
        mix(flatNormal, smoothNormal, curvatureWeight)
    );
    // Use the smoothened TBN if it is well-behaved
    if (determinant(blendedTBN) * orientation > .1)
        TBN = blendedTBN;

    vec3 plane = cross(TBN[0], TBN[1]);
    float detTBN = dot(plane, TBN[2]);
    float rayZ = dot(plane, viewDir);
    // Skip if the ray angle is too oblique
    visibility = min(visibility, smoothstep(.005, .02, rayZ * sign(detTBN) / length(plane)));
    if (visibility <= 0)
        return vec3(0);

    vec3 normalCrossView = cross(TBN[2], viewDir);
    vec2 uvPerHeight = vec2(dot(TBN[1], normalCrossView), -dot(TBN[0], normalCrossView)) / rayZ;
    vec2 deltaUv = uvPerHeight * displacementScale;

    float layerWeight = 1 - 1 / (1 + dot(uvPerHeight, uvPerHeight));
    int numLayers = int(ceil(mix(1.0, 16.0, layerWeight)));
    float layerHeight = 1. / numLayers;

    vec2 rayOrigin = uv + deltaUv * averageHeight;
    float layer = 1;
    float height = 1;
    float prevLayer, prevHeight;
    for (int i = 0; i <= numLayers; i++) {
        prevLayer = layer;
        prevHeight = height;
        layer = max(0, 1 - i * layerHeight);
        height = sampleHeight(material, rayOrigin - deltaUv * layer);
        if (layer <= height)
            break;
    }

    float undershoot = prevLayer - prevHeight;
    float overshoot = height - layer;
    float interval = undershoot + overshoot;
    if (interval > 0)
        layer = mix(layer, prevLayer, overshoot / interval);

    uv += deltaUv * (averageHeight - layer);
    float distancePerHeight = detTBN / rayZ;
    return viewDir * worldScale * displacementScale * (layer - averageHeight) * distancePerHeight;
}

void applyDisplacement(
    inout vec3 fragPos,
    inout vec2 uv1, inout vec2 uv2, inout vec2 uv3,
    const Material material1, const Material material2, const Material material3,
    bool identicalMaterials,
    vec3 texBlend,
    vec3 flatNormal,
    vec3 smoothNormal,
    vec3 viewDir
) {
    bool active1 = material1.displacementMap != -1;
    bool active2 = material2.displacementMap != -1;
    bool active3 = material3.displacementMap != -1;
    if (!active1 && !active2 && !active3)
        return;

    // Capture derivatives before any divergent branching
    mat2 uvGradient1 = mat2(dFdx(uv1), dFdy(uv1));
    mat2 uvGradient2 = mat2(dFdx(uv2), dFdy(uv2));
    mat2 uvGradient3 = mat2(dFdx(uv3), dFdy(uv3));
    mat2x3 worldGradient = mat2x3(dFdx(fragPos), dFdy(fragPos));
    mat2x3 normalGradient = mat2x3(dFdx(smoothNormal), dFdy(smoothNormal));

    vec3 fragDelta;
    vec3 parallaxNormal = dot(flatNormal, viewDir) < 0 ? -flatNormal : flatNormal;
    vec3 delta1 = sampleDisplacementMap(
        uv1, material1, uvGradient1,
        worldGradient, normalGradient,
        parallaxNormal, smoothNormal,
        viewDir
    );
    if (identicalMaterials) {
        uv2 = uv1;
        uv3 = uv1;
        fragDelta = delta1;
    } else {
        vec3 delta2 = sampleDisplacementMap(
            uv2, material2, uvGradient2,
            worldGradient, normalGradient,
            parallaxNormal, smoothNormal,
            viewDir
        );
        vec3 delta3 = sampleDisplacementMap(
            uv3, material3, uvGradient3,
            worldGradient, normalGradient,
            parallaxNormal, smoothNormal,
            viewDir
        );
        fragDelta = delta1 * texBlend.x + delta2 * texBlend.y + delta3 * texBlend.z;
    }

    // Prevent self-shadowing from the current triangle in the shadow map
    vec3 receiverNormal = flatNormal * sign(dot(flatNormal, uboGlobal.lightDir));
    fragDelta -= receiverNormal * min(dot(fragDelta, receiverNormal), 0.0);
    fragPos += fragDelta;
}

#endif
