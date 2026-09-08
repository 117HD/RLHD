#version 330

#include "scene_common.glsl"

#include <utils/oit_common.glsl>
#include <utils/misc.glsl>

#include OIT_MRT_OUTPUTS

uniform sampler2D firstLayerDepth;

in float vViewZ;

void main() {
	vec4 outputColor = shadeFragment();
	if (outputColor.a <= OIT_ALPHA_DISCARD)
		discard;

	ivec2 coords = ivec2(gl_FragCoord.xy);
	vec2 depth = texelFetch(firstLayerDepth, coords, 0).rg;
	float depthScale = drawDistance * TILE_SIZE;
	float firstLayerSample = depth.r * depthScale;
	float lastLayer = depth.g * depthScale;
	float z = abs(vViewZ);

	OitBinLayout bins = oitComputeBinLayout(firstLayerSample, lastLayer, z, OIT_BIN_COUNT);
	bool isOpaque = outputColor.a >= OIT_ALPHA_OPAQUE;

	#include OIT_MRT_INIT

	for (int k = bins.home - 1; k <= bins.home + 1; ++k) {
		if (k < 0 || k >= OIT_BIN_COUNT)
			continue;

		float bw = isOpaque
			? (k == bins.home ? 1.0 : 0.0)
			: oitBinSplatWeight(z, bins.firstLayer + float(k) * bins.binWidth, bins.firstLayer + float(k + 1) * bins.binWidth);
		if (bw < OIT_EPSILON)
			continue;

		#include OIT_MRT_WRITE
	}
}
