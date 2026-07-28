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
#include <utils/shadow_filtering.glsl>

#if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
    #include <utils/shadow_pcss.glsl>
    #define sampleShadow sampleShadowPCSS
#elif SHADOW_FILTERING_KERNEL == 3
    #define sampleShadow sampleShadowPCF3x3
    #define sampleHardwareShadow sampleHardwareShadow3x3
#elif SHADOW_FILTERING_KERNEL == 2
    #define sampleShadow sampleShadowPCF2x2
    #define sampleHardwareShadow sampleHardwareShadow2x2
#else
    #define sampleShadow sampleShadowPCF1x1
    #define sampleHardwareShadow sampleHardwareShadow1x1
#endif

#if SHADOW_MODE != SHADOW_MODE_OFF
float shadowDistanceFade(vec3 fragPos) {
    // Fade out shadows near the shadow map edges
    #if ZONE_RENDERER
        float fadeEnd = max(uboGlobal.shadowDrawDistance, 1.0);
        float fadeStart = max(fadeEnd * .5, fadeEnd - 10.0 * TILE_SIZE);
        return smoothstep(fadeStart, fadeEnd, length(fragPos - uboGlobal.cameraPos));
    #else
        vec4 shadowPos = uboGlobal.lightProjectionMatrix * vec4(fragPos, 1);
        vec2 ndc = shadowPos.xy / shadowPos.w;
        return smoothstep(.75, 1., dot(ndc, ndc));
    #endif
}

float sampleShadowMap(
    vec3 fragPos,
    vec3 surfaceNormal,
    bool preventSelfShadowing,
    bool applyNormalBias,
    bool applyDistanceFade
) {
    if (!uboGlobal.castsShadows)
        return 0.f;

    float fadeOut = applyDistanceFade ? shadowDistanceFade(fragPos) : 0.0;
    if (fadeOut >= 1)
        return 0.f;

    vec4 shadowPos = uboGlobal.lightProjectionMatrix * vec4(fragPos, 1);
    shadowPos.xyz /= shadowPos.w;

    // NDC to texture space
    shadowPos.xyz += 1;
    shadowPos.xyz /= 2;
    vec2 shadowMapSize = vec2(textureSize(shadowMap, 0));
    float bias = 0.0;
    float depthPrecisionBias = 0.0;
    vec3 receiverNormal = surfaceNormal * mat3(uboGlobal.invLightProjectionMatrix);
    // A parallel receiver has unbounded slope. Keep it finite before
    // the per-tap correction is bounded, including zero-offset taps.
    receiverNormal.z = (receiverNormal.z < 0.0 ? -1.0 : 1.0) * max(abs(receiverNormal.z), 1e-7);
    // Filtering follows the geometric receiver plane
    vec2 receiverDepthPerTexel = -receiverNormal.xy / receiverNormal.z / shadowMapSize;

    if (preventSelfShadowing) {
        // Move the receiver plane toward the light along its normal.
        // Shift XY as well as depth so every filter tap evaluates the displaced plane.
        if (applyNormalBias) {
            const float worldSpaceBias = 3.f;
            vec3 lightAxis = uboGlobal.invLightProjectionMatrix[2].xyz;
            vec3 normalOffset = -normalize(surfaceNormal) * sign(dot(surfaceNormal, lightAxis));
            shadowPos.xyz += (mat3(uboGlobal.lightProjectionMatrix) * normalOffset * worldSpaceBias) * 0.5;
        }

        // Bound only the extra safety margin to limit detached shadows at grazing angles.
        bias = clamp(length(receiverDepthPerTexel), uboGlobal.shadowBiasScale, uboGlobal.shadowBiasScale * 16.0);
        // Both the depth texture and packed transparent shadows retain 16 depth bits
        // Cover one truncated depth step plus a step of rounding margin, independently of resolution
        depthPrecisionBias = 2.0 / float(SHADOW_DEPTH_MAX);
    }
    shadowPos.xy = clamp(shadowPos.xy, 0, 1);
    vec4 receiverPlane = vec4(shadowPos.xy * shadowMapSize, receiverDepthPerTexel);

    float shadow = sampleShadow(
        shadowMap,
        SHADOW_TRANSPARENCY == 1,
        shadowPos.z - (bias + depthPrecisionBias),
        shadowPos,
        receiverPlane,
        fragPos
    );

    #if TERRAIN_SHADOWS
        if (shadow < 1.0) {
            vec2 terrainMapSize = vec2(textureSize(terrainShadowMap, 0));
            vec2 texelScale = shadowMapSize / terrainMapSize;
            float terrainBias = bias * texelScale.x + depthPrecisionBias;
            vec4 terrainReceiverPlane = vec4(
                shadowPos.xy * terrainMapSize,
                receiverDepthPerTexel * texelScale
            );
            #if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
                float terrainShadow = sampleShadowPCSS(
                    terrainShadowMap, false, shadowPos.z - terrainBias, shadowPos, terrainReceiverPlane, fragPos);
            #else
                // Hardware PCF shares one reference depth across its bilinear footprint
                if (preventSelfShadowing)
                    terrainBias += dot(abs(terrainReceiverPlane.zw), vec2(1.0));
                float terrainShadow = sampleHardwareShadow(
                    terrainShadowMap, shadowPos.z - terrainBias, shadowPos, terrainReceiverPlane);
            #endif
            shadow = max(shadow, terrainShadow);
        }
    #endif

    return shadow * (1 - fadeOut);
}
#else
#define sampleShadowMap(fragPos, surfaceNormal, preventSelfShadowing, applyNormalBias, applyDistanceFade) 0.0
#endif

#if POSITIONAL_SHADOWS
uniform sampler2DArrayShadow shadowCubemapArray;

const float SHADOW_ATLAS_SIZE = 4096.0;
const int SHADOW_ATLAS_MIN_SIZE_EXP = 5;  // log2(MIN_FACE_RESOLUTION), i.e. log2(32)

const int SHADOW_ATLAS_TIER_BITS = 3;
const int SHADOW_ATLAS_GRID_BITS = 7;

const int SHADOW_ATLAS_NEAR_PLANE_BITS = 14;
const float SHADOW_ATLAS_NEAR_PLANE_SCALE = 16.0;

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

float unpackShadowNearPlane(int packedShadowData) {
	uint p = uint(packedShadowData);
	uint nearBits = p >> uint(SHADOW_ATLAS_TIER_BITS + 2 * SHADOW_ATLAS_GRID_BITS);
	nearBits &= (1u << uint(SHADOW_ATLAS_NEAR_PLANE_BITS)) - 1u;
	return float(nearBits) / SHADOW_ATLAS_NEAR_PLANE_SCALE;
}

vec3 shadowAtlasUV(vec3 dirFromLight, vec2 originPx, float sizePx, out int faceOut) {
	faceOut = shadowSelectFace(dirFromLight);

	vec3 forward = SHADOW_FACE_DIR[faceOut];
	vec3 up = SHADOW_FACE_UP[faceOut];
	vec3 right = normalize(cross(forward, up));
	vec3 trueUp = cross(right, forward);

	float forwardDist = dot(dirFromLight, forward);
	float u =  dot(dirFromLight, right)  / forwardDist;
	// Match the face view matrix: its Y row is trueUp, and framebuffer
	// texture coordinates use the same bottom-to-top orientation as NDC.
	float v = dot(dirFromLight, trueUp) / forwardDist;

	// Face-local UV in [0,1], then remapped into this light's atlas region
	vec2 faceUV = vec2(u, v) * 0.5 + 0.5;
	vec2 atlasUV = (originPx + faceUV * sizePx) / SHADOW_ATLAS_SIZE;

	return vec3(atlasUV, forwardDist);
}

bool debugShadowAtlasUV(vec3 fragPos, vec3 lightPos, int packedShadowData, out vec2 atlasUV, out int faceOut) {
	vec2 originPx;
	float sizePx;
	if (!unpackShadowAtlasRect(packedShadowData, originPx, sizePx)) {
		atlasUV = vec2(-1.0);
		faceOut = -1;
		return false;
	}

	vec3 result = shadowAtlasUV(fragPos - lightPos, originPx, sizePx, faceOut);
	atlasUV = result.xy;
	return true;
}

vec3 debugShadowFaceColor(int faceIndex) {
	if (faceIndex == 0) return vec3(1, 0, 0); // +X red
	if (faceIndex == 1) return vec3(0, 1, 1); // -X cyan
	if (faceIndex == 2) return vec3(0, 1, 0); // +Y green
	if (faceIndex == 3) return vec3(1, 0, 1); // -Y magenta
	if (faceIndex == 4) return vec3(0, 0, 1); // +Z blue
	if (faceIndex == 5) return vec3(1, 1, 0); // -Z yellow
	return vec3(0.5); // no shadow data this frame
}

float samplePositionalShadow(vec3 fragPos, vec3 lightPos, vec3 normal, int packedShadowData, float lightRadiusSq) {
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

	float compareDepth = shadowDistanceToDepth(forwardDist, unpackShadowNearPlane(packedShadowData), sqrt(lightRadiusSq));

	return texture(shadowCubemapArray, vec4(atlasUV, float(face), compareDepth));
}

vec3 debugShadow(vec3 fragPos, vec3 lightPos, vec3 normal, int packedShadowData, float lightRadiusSq) {
    vec2 atlasUV;
	int face;
	debugShadowAtlasUV(fragPos, lightPos, packedShadowData, atlasUV, face);
#if 0
	return debugShadowFaceColor(face) * 100.0;
#else
    return vec3(atlasUV, 0) * 100.0;
#endif
}

#else
#define samplePositionalShadow(fragPos, lightPos, normal, packedShadowData, lightRadiusSq) 1.0
#endif
