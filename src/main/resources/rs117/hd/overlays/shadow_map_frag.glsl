#version 330

uniform sampler2D shadowMap;
uniform sampler2DShadow terrainShadowMap;
uniform bool showTerrainShadowMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
    float shadow;
    if(showTerrainShadowMap) {
        shadow = texture(terrainShadowMap, vec3(fUv, 1.0));
    } else {
        shadow = texture(shadowMap, fUv).r;
    }
    FragColor = vec4(shadow, shadow, shadow, 1);
}
