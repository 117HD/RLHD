#pragma once

#include OIT_BIN_COUNT

#define OIT_EPSILON 1e-4

#define OIT_BIN_SPLAT_SIGMA 0.832555
#define OIT_ALPHA_OPAQUE 0.999
#define OIT_ALPHA_DISCARD 0.001
#define OIT_NO_FIRST_LAYER_THRESHOLD 1e5

struct OitBinLayout {
	float firstLayer;
	float binWidth;
	int home;
};

OitBinLayout oitComputeBinLayout(float firstLayerViewZ, float lastLayerViewZ, float fragViewZ, int binCount) {
	OitBinLayout result;
	result.firstLayer = firstLayerViewZ > OIT_NO_FIRST_LAYER_THRESHOLD ? fragViewZ : firstLayerViewZ;

	float remaining = max(1.0, lastLayerViewZ - result.firstLayer);
	result.binWidth = remaining / float(binCount);
	result.home = clamp(int(floor((fragViewZ - result.firstLayer) / result.binWidth)), 0, binCount - 1);
	return result;
}

float oitBinSplatWeight(float z, float zStart, float zEnd) {
	float halfWidth = max(OIT_EPSILON, (zEnd - zStart) * 0.5);
	float center = zStart + halfWidth;
	float d = (OIT_BIN_SPLAT_SIGMA / halfWidth) * abs(center - z);
	return min(1.0, exp(-d * d) * 2.0);
}

vec3 oitResolveColor(vec4 premultipliedAccum) {
	if (isinf(max(max(abs(premultipliedAccum.r), abs(premultipliedAccum.g)), abs(premultipliedAccum.b))))
		return vec3(0.0);
	return premultipliedAccum.rgb / max(premultipliedAccum.a, OIT_EPSILON);
}
