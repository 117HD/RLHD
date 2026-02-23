/* Copyright (c) 2025, Hooder. Axis-aligned billboard particles. */
#version 330

#include <uniforms/global.glsl>

layout (location = 0) in vec3 aCenter;
layout (location = 1) in vec2 aCorner;
layout (location = 2) in float aSize;
layout (location = 3) in vec4 aColor;

out vec4 vColor;
out vec2 vCorner;
out vec2 vUV;

void main() {
	vec3 toCamera = normalize(cameraPos - aCenter);
	vec3 worldUp = vec3(0, 1, 0);
	vec3 quadUp = worldUp - dot(worldUp, toCamera) * toCamera;
	float len = length(quadUp);
	if (len < 0.001)
		quadUp = vec3(1, 0, 0);
	else
		quadUp /= len;
	vec3 right = normalize(cross(quadUp, toCamera));
	vec3 worldPos = aCenter + (right * aCorner.x + quadUp * aCorner.y) * aSize;
	gl_Position = projectionMatrix * vec4(worldPos, 1.0);
	vColor = aColor;
	vCorner = aCorner;
	vUV = aCorner * 0.5 + 0.5;
}
