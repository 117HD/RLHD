#version 330

#include "scene_common.glsl"

out vec4 FragColor;

void main() {
    vec4 frag = shadeFragment();
#ifndef ALPHA_DISCARD_MSAA
    if (frag.a <= 0.1)
        discard;
#endif

    FragColor = vec4(frag.rgb, frag.a <= 0.1 ? 0.0 : 1.0);
}
