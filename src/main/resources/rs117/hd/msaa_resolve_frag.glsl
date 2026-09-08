#version 330

#include RESOLVE_FUNC

uniform sampler2DMS sourceMS;
uniform int sampleCount;

out float result;

void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);
	float nearest = 1e6;
	for (int i = 0; i < sampleCount; i++)
		nearest = RESOLVE_FUNC(nearest, texelFetch(sourceMS, coords, i).r);

	result = nearest;
}
