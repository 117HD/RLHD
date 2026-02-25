/* Copyright (c) 2025, Hooder. Particle fragment: texture * color, shape from texture alpha. */
#version 330

#include <uniforms/global.glsl>

uniform sampler2DArray uParticleTexture;

in vec4 vColor;
in vec2 vUV;
in float vLayer;

layout (location = 0) out vec4 outColor;

void main() {
	vec4 tex = texture(uParticleTexture, vec3(vUV, vLayer));
	outColor = tex * vColor;
	if (outColor.a <= 0.0)
		discard;
}
