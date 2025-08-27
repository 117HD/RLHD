#version 330

#include <uniforms/global.glsl>

#include <utils/constants.glsl>

uniform sampler2D uniTexture;
uniform int uniMipLevel;
uniform int uniDirection;

in vec2 fUv;

out vec3 FragColor;

void main() {
    const int kernelSize = 5;
    ivec2 direction = ivec2(uniDirection, 1 - uniDirection);
    ivec2 mipSize = textureSize(uniTexture, uniMipLevel);
    vec2 texelSize = 1.f / mipSize;
    float offset = .5 - kernelSize / 2.f;

    vec3 c = vec3(0);
    for (int i = 0; i < kernelSize; i++)
        c += textureLod(uniTexture, fUv + texelSize * direction * (offset + i), uniMipLevel).rgb;
    c /= kernelSize;

    FragColor = c;
}
