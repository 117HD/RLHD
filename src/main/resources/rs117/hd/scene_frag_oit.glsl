#version 330

#include "scene_common.glsl"

#include <utils/oit_common.glsl>
#include <utils/misc.glsl>

#include OIT_MRT_OUTPUTS

uniform sampler2D alphaFirstLastDepth;

in float vViewZ;

void main() {
	vec4 outputColor = shadeFragment();
	if (outputColor.a <= OIT_ALPHA_DISCARD)
		discard;

	ivec2 coords = ivec2(gl_FragCoord.xy);
	vec2 depth = texelFetch(alphaFirstLastDepth, coords, 0).rg;
	float depthScale = uboGlobal.drawDistance * TILE_SIZE;
	float firstLayerSample = depth.r * depthScale;
	float farthestLayer = -depth.g * depthScale;
	float z = abs(vViewZ);

	OitBinLayout bins = oitComputeBinLayout(firstLayerSample, farthestLayer, z, OIT_BIN_COUNT);
	bool isOpaque = outputColor.a >= OIT_ALPHA_OPAQUE;

	#include OIT_MRT_INIT

	if (isOpaque) {
		int k = bins.home;
		float bw = 1.0;
		#include OIT_MRT_WRITE
	} else {
		float halfWidth = max(OIT_EPSILON, bins.binWidth * 0.5);
		float splatScale = OIT_BIN_SPLAT_SIGMA / halfWidth;
		for (int k = max(0, bins.home - 1); k <= min(OIT_BIN_COUNT, bins.home + 1); ++k) {
			float center = bins.firstLayer + (float(k) + 0.5) * bins.binWidth;
			float bw = oitBinSplatWeight(z, center, splatScale);
			if (bw < OIT_EPSILON)
				continue;

		    #include OIT_MRT_WRITE
		}
	}
}
