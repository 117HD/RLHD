#version 330

#include "scene_common.glsl"

in float vViewZ;

layout(location = 0) out vec2 outLayerDepth;
layout(location = 1) out float outCoverage;

void main() {
	vec4 outputColor = shadeFragment();
	if (outputColor.a <= 0.001)
		discard;

	float viewDepth = abs(vViewZ) / (uboGlobal.drawDistance * TILE_SIZE);
	// Store the farthest depth negated so both bounds can use one GL_MIN target.
	outLayerDepth = vec2(viewDepth, -viewDepth);
	outCoverage = outputColor.a;
}
