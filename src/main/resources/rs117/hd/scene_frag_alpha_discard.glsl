#version 330

#include "scene_common.glsl"

out vec4 FragColor;

void main() {
    vec4 frag = shadeFragment();
    if(frag.a <= 0.1)
        discard;
    FragColor = vec4(frag.rgb, 1.0);
}
