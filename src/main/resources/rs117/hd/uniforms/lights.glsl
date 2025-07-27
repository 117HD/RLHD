#pragma once

#include <utils/specular.glsl>

#include MAX_LIGHT_COUNT

struct PointLight {
    vec4 position;
    vec3 color;
};

layout(std140) uniform UBOLights {
    PointLight PointLightArray[MAX_LIGHT_COUNT];
};

void calculateLight(int lightIdx, vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut) {
    PointLight light = PointLightArray[lightIdx];
    vec3 lightToFrag = light.position.xyz - position;
    float distanceSquared = dot(lightToFrag, lightToFrag);
    float radiusSquared = light.position.w;
    if (distanceSquared <= radiusSquared) {
        float attenuation = 1 - sqrt(distanceSquared / radiusSquared);
        attenuation *= attenuation;

        vec3 pointLightColor = light.color * attenuation;
        vec3 pointLightDir = normalize(lightToFrag);

        float pointLightDotNormals = max(dot(normals, pointLightDir), 0);
        pointLightsOut += pointLightColor * pointLightDotNormals;

        vec3 pointLightReflectDir = reflect(-pointLightDir, normals);
        pointLightsSpecularOut += pointLightColor * specular(texBlend, viewDir, pointLightReflectDir, specularGloss, specularStrength);
    }
}

void calculateLighting(vec3 position, vec3 normals, vec3 viewDir,
    vec3 texBlend, vec3 specularGloss, vec3 specularStrength,
    inout vec3 pointLightsOut, inout vec3 pointLightsSpecularOut) {
    #if TILED_LIGHTING
    #if MAX_LIGHTS_PER_TILE > 0
        vec2 screenUV = gl_FragCoord.xy / sceneResolution;
        ivec2 tileXY = ivec2(floor(screenUV * tiledLightingResolution));

        for (int tileLayer = 0; tileLayer < TILED_LIGHTING_LAYER_COUNT; tileLayer++) {
            ivec4 tileLayerData = texelFetch(tiledLightingArray, ivec3(tileXY, tileLayer), 0);
            for(int c = 0; c < 4; c++) {
                int lightIdx = tileLayerData[c] - 1;
                if (lightIdx < 0) {
                    tileLayer = TILED_LIGHTING_LAYER_COUNT + 1;
                    break;
                }

                calculateLight(lightIdx, position, normals, viewDir, texBlend, specularGloss, specularStrength, pointLightsOut, pointLightsSpecularOut);
            }
        }
    #endif
    #else
    for (int lightIdx = 0; lightIdx < pointLightsCount; lightIdx++) {
            calculateLight(lightIdx, position, normals, viewDir, texBlend, specularGloss, specularStrength, pointLightsOut, pointLightsSpecularOut);
    }
    #endif
}