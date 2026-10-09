#version 330

#include <utils/constants.glsl>

uniform sampler2D shadowMap;
#if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
    uniform sampler2D terrainShadowMap;
#else
    uniform sampler2DShadow terrainShadowMap;
#endif
uniform bool showTerrainShadowMap;

in vec2 fUv;

out vec4 FragColor;

void main() {
    float shadow;
    if (showTerrainShadowMap) {
        #if SHADOW_FILTERING == SHADOW_FILTERING_PCSS
            shadow = texture(terrainShadowMap, fUv).r < 1.0 ? 1.0 : 0.0;
        #else
            shadow = texture(terrainShadowMap, vec3(fUv, 1.0));
        #endif
    } else {
        shadow = texture(shadowMap, fUv).r;
    }
    FragColor = vec4(vec3(shadow), 1);
}
