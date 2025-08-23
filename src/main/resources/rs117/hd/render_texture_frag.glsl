#version 330

uniform sampler2D uniTexture;
uniform int uniMipLevel;

in vec2 fUv;

out vec4 FragColor;

void main() {
    FragColor = textureLod(uniTexture, fUv, uniMipLevel);
}
