#version 330

#include <utils/misc.glsl>

uniform sampler2D firstLayerDepth;
uniform sampler2D opaqueSceneDepth;

out vec2 result;

void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);
	float opaqueDepth = texelFetch(opaqueSceneDepth, coords, 0).r;
	float depthScale = drawDistance * TILE_SIZE;
	result = vec2(
		texelFetch(firstLayerDepth, coords, 0).r,
		opaqueViewZ(opaqueDepth) / depthScale
	);
}
