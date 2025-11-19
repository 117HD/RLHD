#include VERSION_HEADER

uniform sampler2D shadowMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
    FragColor = vec4(texture(shadowMap, fUv).rrr, 1);
}
