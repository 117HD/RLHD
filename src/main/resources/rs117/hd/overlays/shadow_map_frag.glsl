#version 330

uniform sampler2D shadowMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec2 uv = fUv;
    uv.y = 1 - uv.y;
    FragColor = vec4(texture(shadowMap, uv).rrr, 1);
}
