#version 330

#include OIT_DEPTH_MERGE_MSAA

uniform sampler2DMS layerDepthMS;

out vec2 result;

void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);

	vec2 layerDepth = texelFetch(layerDepthMS, coords, 0).rg;
	// Both channels encode their reduction as a minimum; the second is negated far depth.
	for (int i = 1; i < OIT_DEPTH_MERGE_MSAA; i++)
		layerDepth = min(layerDepth, texelFetch(layerDepthMS, coords, i).rg);

	result = layerDepth;
}
