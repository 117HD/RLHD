#version 330

#include "scene_common.glsl"

out vec4 FragColor;

void main() {
    FragColor = shadeFragment();
}
