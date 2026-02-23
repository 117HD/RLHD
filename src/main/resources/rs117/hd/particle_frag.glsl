/* Copyright (c) 2025, Hooder. Particle fragment: circle, texture * color. */
#version 330

#include <uniforms/global.glsl>

uniform sampler2D uParticleTexture;

in vec4 vColor;
in vec2 vCorner;
in vec2 vUV;

layout (location = 0) out vec4 outColor;

void main() {
	float r = length(vCorner);
	if (r > 1.0)
		discard;
	outColor = texture(uParticleTexture, vUV) * vColor;
}
