#version 330

#include <uniforms/global.glsl>

layout (location = 0) in vec2 vPos;
layout (location = 1) in vec2 vUv;

out vec3 fRay;

void main() {
    gl_Position = vec4(vPos, 0, 1);

    vec2 ndcUv = (vUv * 2 - 1) * vec2(1, 1);
    const float eps = 1e-10;
    vec4 farPos = invProjectionMatrix * vec4(ndcUv, eps, 1);
    fRay = farPos.xyz / farPos.w;
}
