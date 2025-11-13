#version 330

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

uniform sampler2D shadowMap;
uniform isampler2D shadowTransparencyMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
#if 0
    FragColor = vec4(texture(shadowMap, fUv).rrr, 1);
#else
    ivec2 encoded = texture(shadowTransparencyMap, fUv, 0).rg;
    #if 0
    float depth = float(encoded.r & SHADOW_DEPTH_MAX) / SHADOW_DEPTH_MAX;
    float alpha = float(encoded.r >> SHADOW_DEPTH_BITS) / SHADOW_ALPHA_MAX;
    FragColor = vec4(depth, alpha, 0.0, 1.0);
    #else
    vec3 color = packedHslToSrgb(encoded.g);
    FragColor = vec4(color, 1.0);
    #endif
#endif
}
