#version 330

#include "scene_common.glsl"

out vec4 FragColor;

void main() {
    vec4 frag = shadeFragment();

#ifdef ALPHA_DISCARD_MSAA
    // Alpha-to-coverage converts the fragment's alpha into MSAA sample coverage,
    // so preserve the fractional alpha here.
    FragColor = frag;
#else
    // Without coverage there is no way to represent a partial fragment, so a hard
    // cutout is the only option. 0.5 is the 50% coverage midpoint that
    // alpha-to-coverage would approximate.
    if (frag.a <= 0.5)
        discard;

    FragColor = vec4(frag.rgb, 1.0);
#endif
}