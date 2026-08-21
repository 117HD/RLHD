#version 330

// Layered OIT composite
//
// Upstream passes give us two things per pixel:
//   - colorAccum: OIT_BIN_COUNT depth bins, each holding an accumulated
//     premultiplied color + coverage for fragments near that depth
//     (written by SceneFragTransparencyOIT, with a Gaussian splat across
//     neighboring bins to soften bin edges).
//   - netCoverage: the *exact* total alpha for the pixel, order-independent
//     because it's built from a multiplicative (1-a) blend rather than
//     depth sorting.
//
// We walk the bins front-to-back and use each bin's alpha only as a
// relative *weight* to reconstruct the blended color (standard
// Adst *= (1-a) front-to-back accumulation). The final output alpha isn't
// taken from that sum - it's replaced with the exact netCoverage value, so
// small errors in the binned weights can only bias which layers' colors
// dominate, never the actual opacity.

#include OIT_COMPOSITE_SAMPLE_SHADING

#if OIT_COMPOSITE_SAMPLE_SHADING
    #extension GL_ARB_sample_shading : require
#endif

#include <utils/misc.glsl>
#include <utils/oit_common.glsl>

// 0 - normal composite
// 1 - resolveBin(DEBUG_LAYER) color, unweighted
// 2 - heatmap of DEBUG_LAYER coverage
// 3 - heatmap of total coverage across all bins
// 4 - alpha coverage
#define DEBUG_MODE 0
#define DEBUG_LAYER 0

layout(location = 0) out vec4 frag;

#if OIT_COMPOSITE_SAMPLE_SHADING
	uniform sampler2DMSArray colorAccum;
	uniform sampler2DMS netCoverage;
	#define OIT_SAMPLE_INDEX gl_SampleID
#else
	uniform sampler2DArray colorAccum;
	uniform sampler2D netCoverage;
	#define OIT_SAMPLE_INDEX 0
#endif

// Fetches one bin and returns its resolved (straight, non-premultiplied) color.
// `coverage` receives the bin's own accumulated alpha, used as this bin's blend weight.
vec3 resolveBin(ivec2 coords, int layer, out float coverage) {
	vec4 accum = texelFetch(colorAccum, ivec3(coords, layer), OIT_SAMPLE_INDEX);
	coverage = clamp(accum.a, 0.0, 1.0);
	if (coverage < OIT_EPSILON)
		return vec3(0.0);
	return oitResolveColor(accum);
}

float fetchNetCoverage(ivec2 coords) {
	return texelFetch(netCoverage, coords, OIT_SAMPLE_INDEX).r;
}

#if DEBUG_MODE == 1 || DEBUG_MODE == 2
void main() {
	float coverage;
	vec3 color = resolveBin(ivec2(gl_FragCoord.xy), DEBUG_LAYER, coverage);
	#if DEBUG_MODE == 1
		frag = vec4(color, 1.0);
	#else
		frag = heatmap(coverage, 1.0);
	#endif
}
#elif DEBUG_MODE == 3
void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);
	float totalCoverage = 0.0;
	for (int k = 0; k < OIT_BIN_COUNT; ++k) {
		float coverage;
		resolveBin(coords, k, coverage);
		totalCoverage += coverage;
	}
	frag = heatmap(totalCoverage, 1.0);
}
#elif DEBUG_MODE == 4
void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);
	frag = vec4(vec3(fetchNetCoverage(coords)), 1.0);
}
#else
void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);

	float remainingVisibility = 1.0; // background visibility remaining, front-to-back
	float totalWeight = 0.0;
	vec3 weightedColorSum = vec3(0.0);

	for (int k = 0; k < OIT_BIN_COUNT; ++k) {
		float coverage;
		vec3 color = resolveBin(coords, k, coverage);
		if (coverage < OIT_EPSILON)
			continue;

		float weight = coverage * remainingVisibility;
		weightedColorSum += color * weight;
		totalWeight += weight;
		remainingVisibility *= (1.0 - coverage);

		if (coverage >= 1.0)
			break;
	}

	if (totalWeight < OIT_EPSILON)
		discard;

	vec3 blendedColor = weightedColorSum / totalWeight;
	float finalAlpha = clamp(1.0 - fetchNetCoverage(coords), 0.0, 1.0);
	frag = vec4(blendedColor * finalAlpha, finalAlpha); // premultiplied, blended onto fboScene
}
#endif
