#version 330

#include <uniforms/occlusion.glsl>
#include <uniforms/global.glsl>

layout (location = 0) in vec3 vPosition;

 void main() {
    vec3 position = aabbs[gl_InstanceID * 2];
    vec3 scale = aabbs[gl_InstanceID * 2 + 1];
    vec3 worldPosition = vPosition * scale + position;
    gl_Position = projectionMatrix * vec4(worldPosition, 1.0);
}