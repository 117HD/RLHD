#version 330

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

uniform sampler2DArray colorMap;
uniform int layer;

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec3 c = texture(colorMap, vec3(fUv, layer)).rgb;

    #if LINEAR_ALPHA_BLENDING
        // When linear alpha blending is on, the texture is in sRGB, and OpenGL will automatically convert it to linear
        c = linearToSrgb(c);
    #endif

    FragColor = vec4(c, 1);
}
