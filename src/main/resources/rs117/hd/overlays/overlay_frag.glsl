#version 330

uniform sampler2D shadowMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
    FragColor = vec4(fUv, 0, 1);
}
