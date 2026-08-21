#version 330

uniform sampler2D firstLayerDepth;
uniform sampler2D farthestLayerDepth;

out vec2 result;

void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);
	result = vec2(
		texelFetch(firstLayerDepth, coords, 0).r,
		texelFetch(farthestLayerDepth, coords, 0).r
	);
}
