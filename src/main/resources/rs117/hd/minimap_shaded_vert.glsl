#version 330

layout (location = 0) in vec3 vPos;
layout (location = 1) in vec3 vColor;

uniform mat4 viewProjMatrix;

out vec3 fColor;

void main() {
    gl_Position = viewProjMatrix * vec4(vPos, 1);
    fColor = vColor;
}
