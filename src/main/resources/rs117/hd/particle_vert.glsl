/* Copyright (c) 2025, Mark7625. Instanced billboard particles: one quad mesh, per-instance center/size/color. */
#version 330

#include <uniforms/global.glsl>

layout (location = 0) in vec2 aCorner;
layout (location = 1) in vec3 aCenter;
layout (location = 2) in vec4 aColor;
layout (location = 3) in float aSize;

out vec4 vColor;
out vec2 vCorner;
out vec2 vUV;

void main() {
	vec3 toCamera = cameraPos - aCenter;
	toCamera *= inversesqrt(max(dot(toCamera, toCamera), 1e-12));
	vec3 worldUp = vec3(0, 1, 0);
	vec3 right = cross(worldUp, toCamera);
	float lenSq = dot(right, right);
	right = mix(vec3(1, 0, 0), right * inversesqrt(lenSq), step(1e-6, lenSq));
	vec3 up = cross(toCamera, right);
	vec3 worldPos = aCenter + (right * aCorner.x + up * aCorner.y) * aSize;
	gl_Position = projectionMatrix * vec4(worldPos, 1.0);
	vColor = aColor;
	vCorner = aCorner;
	vUV = aCorner * 0.5 + 0.5;
}
