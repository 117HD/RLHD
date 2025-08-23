#version 330

#include <utils/constants.glsl>

uniform sampler2D uniTexture;
uniform int uniMipLevel;
uniform int uniDirection;

in vec2 fUv;

out vec3 FragColor;

void main() {
    const int kernelSize = 9;
    const float sigma = 3;
    ivec2 direction = ivec2(uniDirection, 1 - uniDirection);
    ivec2 offset = ivec2(gl_FragCoord.xy) - direction * kernelSize / 2;
    vec3 c = vec3(0);

    vec2 texelSize = 1.f / textureSize(uniTexture, uniMipLevel);
    for (int i = 0; i < kernelSize; i++)
        c += textureLod(uniTexture, fUv + texelSize * direction * (i - kernelSize / 2), uniMipLevel).rgb;
    c /= kernelSize;

//    for (int i = 0; i < kernelSize; i++)
//        c += texelFetch(uniTexture, offset + i * direction, uniMipLevel).rgb;
//    c /= kernelSize;
//    const float s2 = 2 * sigma * sigma;
//    for (int i = 0; i < kernelSize; i++) {
//        float x = i - kernelSize / 2.f;
//        c += exp(-x*x / s2) / sqrt(PI * s2) * 1.7 * texelFetch(uniTexture, offset + i * direction, uniMipLevel).rgb;
//    }
    FragColor = c;
}
