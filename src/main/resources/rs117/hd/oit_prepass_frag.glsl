#version 330

#include "scene_common.glsl"

in float vViewZ;

layout(location = 0) out float outFirstLayerDepth;
layout(location = 1) out float outCoverage;

void main() {
	vec4 outputColor = shadeFragment();
	if (outputColor.a <= 0.001)
		discard;

    float viewDepth = abs(vViewZ) / (drawDistance * TILE_SIZE);
    outFirstLayerDepth = viewDepth;
    outCoverage = outputColor.a;
}
