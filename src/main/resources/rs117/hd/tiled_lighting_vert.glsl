#version 330

#include <uniforms/global.glsl>

layout (location = 0) in vec2 vPos;
layout (location = 1) in vec2 vUv;

out vec2 fUv;

void main() {
    gl_Position = vec4(vPos, 0, 1);
    fUv = vUv;
}
