#version 330

layout (location = 0) in vec3 vPos;
layout (location = 1) in vec3 vColor;
layout (location = 2) in vec2 vLocalUv;
layout (location = 3) in vec2 vOffset;

uniform vec2 focusWorld;
uniform float cosYaw;
uniform float sinYaw;
uniform float pixelToWorld;
uniform vec2 fboSize;
uniform float minHalfThicknessPx;

out vec3 fColor;
out vec2 fLocalUv;
out vec2 fPixelOffset;

void main() {
    float offsetLen = length(vOffset);
    float minOffsetWorld = minHalfThicknessPx * pixelToWorld;
    vec2 appliedOffset = offsetLen > 1e-6 ? vOffset * (max(offsetLen, minOffsetWorld) / offsetLen) : vec2(0.0);

    float dx = (vPos.x + appliedOffset.x) - focusWorld.x;
    float dz = (vPos.z + appliedOffset.y) - focusWorld.y;

    vec2 pixelOffset = vec2(
        (cosYaw * dx + sinYaw * dz) / pixelToWorld,
        (cosYaw * dz - sinYaw * dx) / pixelToWorld
    );

    fColor = vColor;
    fLocalUv = vLocalUv;
    fPixelOffset = pixelOffset;
    gl_Position = vec4(pixelOffset / (fboSize * 0.5), 0, 1);
}
