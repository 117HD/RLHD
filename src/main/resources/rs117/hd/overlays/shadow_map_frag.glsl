#version 330

uniform sampler2D shadowMap;
uniform sampler2DArray positionalShadowMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
#if 1
    FragColor = vec4(texture(positionalShadowMap, vec3(fUv, 0)).rrr, 1);
#else
    FragColor = vec4(texture(shadowMap, fUv).rrr * 1.5, 1);
#endif
}
