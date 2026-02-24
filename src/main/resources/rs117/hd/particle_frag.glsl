/* Copyright (c) 2025, Hooder. Particle fragment: texture * color, shape from texture alpha. */
#version 330

#include <uniforms/global.glsl>

uniform sampler2D uParticleTexture;

in vec4 vColor;
in vec2 vUV;

layout (location = 0) out vec4 outColor;

void main() {
	vec4 tex = texture(uParticleTexture, vUV);
	outColor = tex * vColor;
	if (outColor.a <= 0.0)
		discard;
}
