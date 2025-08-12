#pragma once

#include <uniforms/lights.glsl>

#include <utils/constants.glsl>
#include <utils/specular.glsl>

#if DYNAMIC_LIGHTS
void calculateLight(
    int lightIdx, vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut
) {
    PointLight light = PointLightArray[lightIdx];
    vec3 lightToFrag = light.position.xyz - position;
    float distanceSquared = dot(lightToFrag, lightToFrag);
    float radiusSquared = light.position.w;
    if (distanceSquared <= radiusSquared) {
        float attenuation = 1 - sqrt(distanceSquared / radiusSquared);
        attenuation *= attenuation;

        vec3 pointLightColor = light.color.rgb * attenuation;
        vec3 pointLightDir = normalize(lightToFrag);

        float pointLightDotNormals = max(dot(normals, pointLightDir), 0);
        pointLightsOut += pointLightColor * pointLightDotNormals;

        vec3 pointLightReflectDir = reflect(-pointLightDir, normals);
        pointLightsSpecularOut += pointLightColor * specular(texBlend, viewDir, pointLightReflectDir, specularGloss, specularStrength);
    }
}

void calculateLighting(
    vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut
) {
    #if TILED_LIGHTING
        ivec2 tileXY = ivec2(floor(gl_FragCoord.xy / sceneResolution * tiledLightingResolution));

        for (int tileLayer = 0; tileLayer < TILED_LIGHTING_LAYER_COUNT; tileLayer++) {
            ivec4 tileLayerData = texelFetch(tiledLightingArray, ivec3(tileXY, tileLayer), 0);

            #define PROCESS_TILED_LIGHT_COMPONENT(c)            \
                if (tileLayerData[c] <= 0)                      \
                    break;                                      \
                calculateLight(tileLayerData[c] - 1,            \
                    position, normals, viewDir,                 \
                    texBlend, specularGloss, specularStrength,  \
                    pointLightsOut, pointLightsSpecularOut);

            PROCESS_TILED_LIGHT_COMPONENT(0);
            PROCESS_TILED_LIGHT_COMPONENT(1);
            PROCESS_TILED_LIGHT_COMPONENT(2);
            PROCESS_TILED_LIGHT_COMPONENT(3);
        }
    #else
        for (int lightIdx = 0; lightIdx < pointLightsCount; lightIdx++)
            calculateLight(lightIdx, position, normals, viewDir,
                texBlend, specularGloss, specularStrength,
                pointLightsOut, pointLightsSpecularOut);
    #endif
}
#else
#define calculateLighting(position, normals, viewDir, texBlend, specularGloss, specularStrength, pointLightsOut,  pointLightsSpecularOut)
#endif
