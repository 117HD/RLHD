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
#include <utils/misc.glsl>

#if SHADOW_RESOLUTION == 0
    #define MIN_SHADOW_BIAS -0.00125f
#elif SHADOW_RESOLUTION == 1
    #define MIN_SHADOW_BIAS -0.0007f
#elif SHADOW_RESOLUTION == 2
    #define MIN_SHADOW_BIAS -0.00035
#elif SHADOW_RESOLUTION == 3
    #define MIN_SHADOW_BIAS -0.0003
#elif SHADOW_RESOLUTION >= 4
    #define MIN_SHADOW_BIAS -0.00025
#endif

#ifndef TILE_SIZE
    #define TILE_SIZE 128
#endif

#if SHADOW_MODE != SHADOW_MODE_OFF
float fetchShadowTexel(ivec2 pixelCoord, float fragDepth, vec3 fragPos, int i) {
    #if SHADOW_FILTERING == SHADOW_FILTERING_DITHER
        int index = int(hash(vec4(floor(fragPos.xyz), i)) * POISSON_DISK_LENGTH) % POISSON_DISK_LENGTH;
        pixelCoord += ivec2(getPoissonDisk(index) * 1.25);
    #endif

    #if SHADOW_TRANSPARENCY
        int alphaDepth = int(texelFetch(shadowMap, pixelCoord, 0).r * SHADOW_COMBINED_MAX);
        float depth = float(alphaDepth & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
        float alpha = 1 - float(alphaDepth >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
        return depth < fragDepth ? alpha : 0.f;
    #else
        return texelFetch(shadowMap, pixelCoord, 0).r < fragDepth ? 1.f : 0.f;
    #endif
}

float sampleShadowMap(vec3 fragPos, vec2 distortion, float lightDotNormals) {
    if (lightStrength <= 0)
        return 0.f;

    vec4 shadowPos = lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    // Fade out shadows near the shadow map edges
    #if ZONE_RENDERER
        // TODO: Make this configurable if we make the Shadow Distance Variable
        const float fadeStart = 55.0 * TILE_SIZE;
        const float fadeEnd   = 65.0 * TILE_SIZE;
        float fadeOut = smoothstep(fadeStart, fadeEnd, length(fragPos - cameraPos));
    #else
        float fadeOut = smoothstep(.75, 1., dot(shadowPos.xy, shadowPos.xy));
    #endif
    if (fadeOut >= 1)
        return 0.f;

    // NDC to texture space
    ivec2 shadowRes = textureSize(shadowMap, 0);
    shadowPos.xyz += 1;
    shadowPos.xyz /= 2;
    shadowPos.xy += distortion;
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    shadowPos.xy *= shadowRes;
    shadowPos.xy += .5; // Shift to texel center

    float shadowBias = MIN_SHADOW_BIAS * max(1, 1.0 - lightDotNormals);
    float fragDepth = shadowPos.z + shadowBias;

    const int kernelSize = 3;
    ivec2 kernelOffset = ivec2(shadowPos.xy - kernelSize / 2);
    #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
        const float kernelAreaReciprocal = 1. / (kernelSize * kernelSize);
    #else
        const float kernelAreaReciprocal = .25; // This is effectively a 2x2 kernel
        vec2 lerp = fract(shadowPos.xy);
        vec3 lerpX = vec3(1 - lerp.x, 1, lerp.x);
        vec3 lerpY = vec3(1 - lerp.y, 1, lerp.y);
    #endif

    // Sample 4 corners first
    float c00 = fetchShadowTexel(kernelOffset + ivec2(0, 0), fragDepth, fragPos, 0);
    float c02 = fetchShadowTexel(kernelOffset + ivec2(0, kernelSize - 1), fragDepth, fragPos, 1);
    float c20 = fetchShadowTexel(kernelOffset + ivec2(kernelSize - 1, 0), fragDepth, fragPos, 2);
    float c22 = fetchShadowTexel(kernelOffset + ivec2(kernelSize - 1, kernelSize - 1), fragDepth, fragPos, 3);

    // Early exit if all corners are the same (fully shadowed or fully lit)
    bool allShadowed = (c00 == 0.0 && c02 == 0.0 && c20 == 0.0 && c22 == 0.0);
    bool allLit      = (c00 == 1.0 && c02 == 1.0 && c20 == 1.0 && c22 == 1.0);

    float shadow = 0.0;
    if (allShadowed || allLit) {
        shadow = (c00 + c02 + c20 + c22) * 0.25;
    } else {
        // Finish sampling the reset of the kernal
        float s01 = fetchShadowTexel(kernelOffset + ivec2(0, 1), fragDepth, fragPos, 4);
        float s10 = fetchShadowTexel(kernelOffset + ivec2(1, 0), fragDepth, fragPos, 5);
        float s11 = fetchShadowTexel(kernelOffset + ivec2(1, 1), fragDepth, fragPos, 6);
        float s12 = fetchShadowTexel(kernelOffset + ivec2(1, 2), fragDepth, fragPos, 7);
        float s21 = fetchShadowTexel(kernelOffset + ivec2(2, 1), fragDepth, fragPos, 8);

        #if SHADOW_FILTERING == SHADOW_FILTERING_AVERAGE
            shadow =
                c00 + s01 + c02 +
                s10 + s11 + s12 +
                c20 + s21 + c22;
        #else
            shadow =
                c00 * lerpX[0] * lerpY[0] +
                s01 * lerpX[0] * lerpY[1] +
                c02 * lerpX[0] * lerpY[2] +
                s10 * lerpX[1] * lerpY[0] +
                s11 * lerpX[1] * lerpY[1] +
                s12 * lerpX[1] * lerpY[2] +
                c20 * lerpX[2] * lerpY[0] +
                s21 * lerpX[2] * lerpY[1] +
                c22 * lerpX[2] * lerpY[2];
        #endif
        shadow *= kernelAreaReciprocal;
    }

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, distortion, lightDotNormals) 0
#endif

#if POSITIONAL_SHADOWS
uniform sampler2DArrayShadow shadowCubemapArray;

const float SHADOW_NEAR_PLANE = 10.0;

const float SHADOW_ATLAS_SIZE = 4096.0;
const int SHADOW_ATLAS_MIN_SIZE_EXP = 5;
const int SHADOW_ATLAS_TIER_BITS = 3;
const int SHADOW_ATLAS_GRID_BITS = 7;

const float NORMAL_BIAS_TEXELS = 10.0;

const vec3 SHADOW_FACE_DIR[6] = vec3[](
	vec3( 1,  0,  0), vec3(-1,  0,  0),
	vec3( 0,  1,  0), vec3( 0, -1,  0),
	vec3( 0,  0,  1), vec3( 0,  0, -1)
);
const vec3 SHADOW_FACE_UP[6] = vec3[](
	vec3(0, -1,  0), vec3(0, -1,  0),
	vec3(0,  0,  1), vec3(0,  0, -1),
	vec3(0, -1,  0), vec3(0, -1,  0)
);

float shadowDistanceToDepth(float dist, float near, float far) {
	float a = (1.0 + near / far) / (near / far - 1.0);
	float b = a * near - near;
	float ndcZ = -a + b / dist;
	return ndcZ * 0.5 + 0.5;
}

int shadowSelectFace(vec3 dir) {
	vec3 a = abs(dir);
	if (a.x >= a.y && a.x >= a.z)
		return dir.x > 0.0 ? 0 : 1;
	if (a.y >= a.z)
		return dir.y > 0.0 ? 2 : 3;
	return dir.z > 0.0 ? 4 : 5;
}

bool unpackShadowAtlasRect(int packedShadowData, out vec2 originPx, out float sizePx) {
	if (packedShadowData < 0)
		return false;

	uint p = uint(packedShadowData);
	uint tierMask = (1u << SHADOW_ATLAS_TIER_BITS) - 1u;
	uint gridMask = (1u << SHADOW_ATLAS_GRID_BITS) - 1u;

	uint tier  = p & tierMask;
	uint gridX = (p >> SHADOW_ATLAS_TIER_BITS) & gridMask;
	uint gridY = (p >> (SHADOW_ATLAS_TIER_BITS + SHADOW_ATLAS_GRID_BITS)) & gridMask;

	int exponent = int(tier) + SHADOW_ATLAS_MIN_SIZE_EXP;
	sizePx = float(1 << exponent);
	originPx = vec2(gridX, gridY) * sizePx;
	return true;
}

vec3 shadowAtlasUV(vec3 dirFromLight, vec2 originPx, float sizePx, out int faceOut) {
	faceOut = shadowSelectFace(dirFromLight);

	vec3 forward = SHADOW_FACE_DIR[faceOut];
	vec3 up = SHADOW_FACE_UP[faceOut];
	vec3 right = normalize(cross(forward, up));
	vec3 trueUp = cross(right, forward);

	float forwardDist = dot(dirFromLight, forward);
	float u =  dot(dirFromLight, right)  / forwardDist;
	float v = dot(dirFromLight, trueUp) / forwardDist;

	vec2 faceUV = vec2(u, v) * 0.5 + 0.5;
	vec2 atlasUV = (originPx + faceUV * sizePx) / SHADOW_ATLAS_SIZE;

	return vec3(atlasUV, forwardDist);
}

float sampleShadow(vec3 fragPos, vec3 lightPos, vec3 normal, int packedShadowData, float lightRadius, float lightNearPlane) {
	vec2 originPx;
	float sizePx;
	if (!unpackShadowAtlasRect(packedShadowData, originPx, sizePx))
		return 1.0; // no shadow data for this light this frame -> fully lit

	vec3 toFrag = fragPos - lightPos;
	float dist = length(toFrag);
	vec3 dirNorm = toFrag / dist;

	float NdotL = clamp(dot(normal, -dirNorm), 0.0, 1.0);
	float slopeScale = 1.0 - NdotL;

	float texelWorldSize = (2.0 * dist) / sizePx;
	float normalOffset = texelWorldSize * NORMAL_BIAS_TEXELS * slopeScale;

	vec3 biasedFragPos = fragPos + normal * normalOffset;
	vec3 biasedDir = biasedFragPos - lightPos;

	int face;
	vec3 uvAndDist = shadowAtlasUV(biasedDir, originPx, sizePx, face);
	vec2 atlasUV = uvAndDist.xy;
	float forwardDist = uvAndDist.z;

	float compareDepth = shadowDistanceToDepth(forwardDist, lightNearPlane, lightRadius);

	return texture(shadowCubemapArray, vec4(atlasUV, float(face), compareDepth));
}

#else
#define sampleShadow(fragPos, lightPos, normal, packedShadowData, lightRadius, lightNearPlane) 1.0
#endif
