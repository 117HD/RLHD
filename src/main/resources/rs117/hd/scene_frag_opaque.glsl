#version 330

#include "scene_common.glsl"

out vec4 FragColor;

void main() {
    FragColor = vec4(shadeFragment().rgb, 1.0);
}
