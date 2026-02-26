#version 330

#include <uniforms/global.glsl>

layout (location = 0) in vec3 vVertex;
layout (location = 1) in vec3 vCenter;
layout (location = 2) in vec3 vScale;

 void main() {
    vec3 worldPosition = vVertex * vScale + vCenter;
    gl_Position = projectionMatrix * vec4(worldPosition, 1.0);
}