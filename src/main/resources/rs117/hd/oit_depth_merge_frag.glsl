#version 330

#include OIT_DEPTH_MERGE_MSAA

#if OIT_DEPTH_MERGE_MSAA
uniform sampler2DMS firstLayerDepthMS;
uniform sampler2DMS farthestLayerDepthMS;
#else
uniform sampler2D firstLayerDepth;
uniform sampler2D farthestLayerDepth;
#endif

out vec2 result;

void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);

#if OIT_DEPTH_MERGE_MSAA
	float first = texelFetch(firstLayerDepthMS, coords, 0).r;
	float farthest = texelFetch(farthestLayerDepthMS, coords, 0).r;
	for (int i = 1; i < OIT_DEPTH_MERGE_MSAA; i++) {
		first = min(first, texelFetch(firstLayerDepthMS, coords, i).r);
		farthest = max(farthest, texelFetch(farthestLayerDepthMS, coords, i).r);
	}
	result = vec2(first, farthest);
#else
	result = vec2(
		texelFetch(firstLayerDepth, coords, 0).r,
		texelFetch(farthestLayerDepth, coords, 0).r
	);
#endif
}
