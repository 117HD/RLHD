#version 330

uniform vec4 transform;

layout (location = 0) in vec2 vPos;
layout (location = 1) in vec2 vUv;

out vec2 fUv;

void main() {
    vec2 translation = transform.xy;
    vec2 scale = transform.zw;
    gl_Position = vec4(vPos * scale + translation, 0, 1);
    fUv = vUv;
}
