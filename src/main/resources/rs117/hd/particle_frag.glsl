/* Copyright (c) 2025, Hooder. Particle fragment: texture * color, shape from texture alpha. */
#version 330

#include <uniforms/global.glsl>

uniform sampler2DArray uParticleTexture;

in vec4 vColor;
in vec2 vUV;
in float vLayer;
in vec3 vFlipbook;

layout (location = 0) out vec4 outColor;

void main() {
	vec2 uv = vUV;
	float cols = vFlipbook.x;
	float rows = vFlipbook.y;
	float frameVal = vFlipbook.z;
	if (cols > 0.0 && rows > 0.0) {
		float numFrames = cols * rows;
		float frame = frameVal >= 1.0 ? floor(frameVal - 1.0) : floor(frameVal * numFrames);
		frame = clamp(frame, 0.0, numFrames - 1.0);
		float col = mod(frame, cols);
		float row = floor(frame / cols);
		uv = (uv + vec2(col, row)) / vec2(cols, rows);
	}
	vec4 tex = texture(uParticleTexture, vec3(uv, vLayer));
	outColor = tex * vColor;
	if (outColor.a <= 0.0)
		discard;
}
