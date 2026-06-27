#version 330

uniform sampler2D shadowMap;
uniform sampler2D terrainShadowMap;
uniform bool showTerrainShadowMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
    if(showTerrainShadowMap) {
        FragColor = vec4(texture(terrainShadowMap, fUv).rrr, 1);
    } else {
        FragColor = vec4(texture(shadowMap, fUv).rrr, 1);
    }
}
