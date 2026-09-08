#version 330

#include "scene_common.glsl"

out vec3 FragColor;

void main() {
    FragColor = shadeFragment().rgb;
}
