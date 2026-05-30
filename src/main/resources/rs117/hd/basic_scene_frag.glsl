#version 330

#include <uniforms/global.glsl>
#include <utils/constants.glsl>

uniform vec4 color;

out vec4 FragColor;

void main() {
    FragColor = color;
}
