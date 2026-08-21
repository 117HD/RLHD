#version 330

#include RESOLVE_FUNC

uniform sampler2DMS sourceMS;
uniform int sampleCount;

out float result;

void main() {
	ivec2 coords = ivec2(gl_FragCoord.xy);
	float value = texelFetch(sourceMS, coords, 0).r;
	for (int i = 1; i < sampleCount; i++)
		value = RESOLVE_FUNC(value, texelFetch(sourceMS, coords, i).r);

	result = value;
}
