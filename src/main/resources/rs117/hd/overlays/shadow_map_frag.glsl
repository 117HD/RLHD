#version 330

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

uniform sampler2D shadowMap;
uniform isampler2D shadowTransparencyMap;
uniform isampler2D shadowGroundMask;

in vec2 fUv;

out vec4 FragColor;

void main() {
#if 0
    FragColor = vec4(texture(shadowMap, fUv).rrr, 1);
#elif 1
    int shadowGroundTileXY = texture(shadowGroundMask, fUv).r;
    int shadowGroundTileExX = shadowGroundTileXY & 0xFF;
    int shadowGroundTileExY = (shadowGroundTileXY >> 8) & 0xFF;
    FragColor = vec4(float(shadowGroundTileExX) / 255.0, float(shadowGroundTileExY) / 255.0, 0, 1.0);
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
