/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#version 330

#include uniforms/materials.glsl
#include uniforms/water_types.glsl
#include uniforms/lights.glsl

#include MATERIAL_CONSTANTS

uniform sampler2DArray textureArray;
uniform sampler2D shadowMap;

uniform vec3 cameraPos;
uniform mat4 lightProjectionMatrix;
uniform float elapsedTime;
uniform float colorBlindnessIntensity;
uniform vec3 fogColor;
uniform float fogDepth;
uniform vec3 waterColorLight;
uniform vec3 waterColorMid;
uniform vec3 waterColorDark;
uniform vec3 ambientColor;
uniform float ambientStrength;
uniform vec3 lightColor;
uniform float lightStrength;
uniform vec3 underglowColor;
uniform float underglowStrength;
uniform float groundFogStart;
uniform float groundFogEnd;
uniform float groundFogOpacity;
uniform float lightningBrightness;
uniform vec3 lightDir;
uniform float shadowMaxBias;
uniform int shadowsEnabled;
uniform bool underwaterEnvironment;
uniform bool underwaterCaustics;
uniform vec3 underwaterCausticsColor;
uniform float underwaterCausticsStrength;

// general HD settings
uniform float saturation;
uniform float contrast;

uniform int pointLightsCount; // number of lights in current frame

flat in vec4 vColor[3];
flat in vec3 vUv[3];
flat in int vMaterialData[3];
flat in int vTerrainData[3];
flat in vec3 T;
flat in vec3 B;

in FragmentData {
    vec3 position;
    vec3 normal;
    vec3 texBlend;
    float fogAmount;
} IN;

out vec4 FragColor;

vec2 worldUvs(float scale) {
    return -IN.position.xz / (128 * scale);
}

#include utils/constants.glsl
#include utils/misc.glsl
#include utils/color_blindness.glsl
#include utils/caustics.glsl
#include utils/color_utils.glsl
#include utils/normals.glsl
#include utils/specular.glsl
#include utils/displacement.glsl
#include utils/shadows.glsl
#include utils/water.glsl
#include utils/structs.glsl
#include utils/helpers.glsl
#include utils/lighting.glsl

void main() {
    Scene scene;
    scene.texBlend = IN.texBlend;
    scene.viewDir = normalize(cameraPos - IN.position);
    scene.downDir = vec3(0, -1, 0);

    scene.sun.type = LIGHT_DIRECTIONAL;
    scene.sun.color = lightColor * lightStrength;
    scene.sun.brightness = lightStrength;

    scene.materials[0] = getMaterial(vMaterialData[0] >> MATERIAL_INDEX_SHIFT);
    scene.materials[1] = getMaterial(vMaterialData[1] >> MATERIAL_INDEX_SHIFT);
    scene.materials[2] = getMaterial(vMaterialData[2] >> MATERIAL_INDEX_SHIFT);

    scene.uvs[0] = vUv[0].xy;
    scene.uvs[1] = vUv[1].xy;
    scene.uvs[2] = vUv[2].xy;
    scene.uvs[3] = scene.uvs[0] * scene.texBlend.x + scene.uvs[1] * scene.texBlend.y + scene.uvs[2] * scene.texBlend.z;

    scene.mipBias = 0;

    // Water data
    bool isTerrain = (vTerrainData[0] & 1) != 0; // 1 = 0b1
    int waterDepth1 = vTerrainData[0] >> 8 & 0x7FF;
    int waterDepth2 = vTerrainData[1] >> 8 & 0x7FF;
    int waterDepth3 = vTerrainData[2] >> 8 & 0x7FF;
    float waterDepth =
        waterDepth1 * IN.texBlend.x +
        waterDepth2 * IN.texBlend.y +
        waterDepth3 * IN.texBlend.z;
    int waterTypeIndex = isTerrain ? vTerrainData[0] >> 3 & 0x1F : 0;
    WaterType waterType = getWaterType(waterTypeIndex);

    // TODO:: Move this into scene renderinfo
    // set initial texture map ids
    int colorMap1 = scene.materials[0].colorMap;
    int colorMap2 = scene.materials[1].colorMap;
    int colorMap3 = scene.materials[2].colorMap;

    bool isUnderwater = waterDepth != 0;
    bool isWater = waterTypeIndex > 0 && !isUnderwater;

    vec4 outputColor = vec4(1);

    if (isWater)
    {
        outputColor = sampleWater(waterTypeIndex, scene.viewDir);
    }
    else
    {
        adjustSceneUvs(scene);
        applyFlowmapToUvs(scene, isUnderwater, waterType);
        getSceneNormals(scene, vMaterialData[0]);
        populateLightVectors(scene.sun, lightDir, scene.normals);
        populateLightDotProducts(scene.sun, scene, scene.normals);
        adjustFragPos(scene, IN.position);

        // get vertex colors
        vec4 flatColor = vec4(0.5, 0.5, 0.5, 1.0);
        vec4 baseColor1 = vColor[0];
        vec4 baseColor2 = vColor[1];
        vec4 baseColor3 = vColor[2];
        adjustVertexColors(baseColor1, baseColor2, baseColor3);

        // get diffuse textures
        vec4 texColor1 = colorMap1 == -1 ? vec4(1) : texture(textureArray, vec3(scene.uvs[0], colorMap1), scene.mipBias);
        vec4 texColor2 = colorMap2 == -1 ? vec4(1) : texture(textureArray, vec3(scene.uvs[1], colorMap2), scene.mipBias);
        vec4 texColor3 = colorMap3 == -1 ? vec4(1) : texture(textureArray, vec3(scene.uvs[2], colorMap3), scene.mipBias);
        texColor1.rgb *= scene.materials[0].brightness;
        texColor2.rgb *= scene.materials[1].brightness;
        texColor3.rgb *= scene.materials[2].brightness;

        // get fragment colors by combining vertex colors and texture samples
        vec4 texA = getMaterialShouldOverrideBaseColor(scene.materials[0]) ? texColor1 : vec4(texColor1.rgb * baseColor1.rgb, min(texColor1.a, baseColor1.a));
        vec4 texB = getMaterialShouldOverrideBaseColor(scene.materials[1]) ? texColor2 : vec4(texColor2.rgb * baseColor2.rgb, min(texColor2.a, baseColor2.a));
        vec4 texC = getMaterialShouldOverrideBaseColor(scene.materials[2]) ? texColor3 : vec4(texColor3.rgb * baseColor3.rgb, min(texColor3.a, baseColor3.a));

        // underlay / overlay color contribution
        vec4 underlayColor = vec4(0.0);
        vec4 overlayColor = vec4(0.0);
        float overlayMix = 0.0;
        getOverlayUnderlayColorBlend(vMaterialData, scene.uvs[3], IN.texBlend, texA, texB, texC, overlayColor, underlayColor, overlayMix);

        outputColor = mix(underlayColor, overlayColor, overlayMix);

        // specular
        vec3 vSpecularGloss = vec3(scene.materials[0].specularGloss, scene.materials[1].specularGloss, scene.materials[2].specularGloss);
        vec3 vSpecularStrength = vec3(scene.materials[0].specularStrength, scene.materials[1].specularStrength, scene.materials[2].specularStrength);
        vSpecularStrength *= vec3(
            scene.materials[0].roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(scene.uvs[0], scene.materials[0].roughnessMap)).r),
            scene.materials[1].roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(scene.uvs[1], scene.materials[1].roughnessMap)).r),
            scene.materials[2].roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(scene.uvs[2], scene.materials[2].roughnessMap)).r)
        );

        // apply specular highlights to anything semi-transparent
        // this isn't always desirable but adds subtle light reflections to windows, etc.
        if (baseColor1.a + baseColor2.a + baseColor3.a < 2.99)
        {
            vSpecularGloss = vec3(30);
            vSpecularStrength = vec3(
                clamp((1 - baseColor1.a) * 2, 0, 1),
                clamp((1 - baseColor2.a) * 2, 0, 1),
                clamp((1 - baseColor3.a) * 2, 0, 1)
            );
        }
        float combinedSpecularStrength = dot(vSpecularStrength, IN.texBlend);

        // underwater caustics based on directional light
        if (underwaterCaustics && underwaterEnvironment) {
            float scale = 12.8;
            vec2 causticsUv = worldUvs(scale);

            // height offset
            causticsUv += scene.sun.direction.xy * IN.position.y / (128 * scale);

            const ivec2 direction = ivec2(1, -1);
            const int driftSpeed = 231;
            vec2 drift = animationFrame(231) * ivec2(1, -2);
            vec2 flow1 = causticsUv + animationFrame(19) * direction + drift;
            vec2 flow2 = causticsUv * 1.25 + animationFrame(37) * -direction + drift;

            vec3 caustics = sampleCaustics(flow1, flow2) * 2;

            vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
            scene.sun.color += caustics * causticsColor * scene.sun.ndl * pow(scene.sun.brightness, 1.5);
        }

        vec3 ambientTerm = ambientTerm(scene, fogColor, ambientStrength);
        vec3 diffuseTerm = max(scene.sun.ndl, 0.0) * vec3(0.0) * lightAttenuation(scene.sun, scene.fragPos, waterTypeIndex, vMaterialData[0]);
        vec3 specularTerm = scene.sun.color * specular(scene.viewDir, scene.sun.reflection, vSpecularGloss, vSpecularStrength);

        // point lights
        vec3 additiveLights = vec3(0);
        vec3 additiveLightsSpecular = vec3(0);
        gatherAdditiveLights(additiveLights, additiveLightsSpecular, scene, vSpecularGloss, vSpecularStrength, waterTypeIndex, vMaterialData[0]);

        // lightning
        vec3 lightningColor = vec3(.25, .25, .25);
        float lightningStrength = lightningBrightness;
        float lightningDotNormals = scene.ddn;
        vec3 lightningOut = max(lightningDotNormals, 0.0) * lightningColor * lightningStrength;

        // underglow
        vec3 underglowOut = underglowColor * max(scene.normals.y, 0) * underglowStrength;

        // apply lighting
        vec3 compositeLight = diffuseTerm + ambientTerm + specularTerm + additiveLights + additiveLightsSpecular + underglowOut + lightningOut ;

        outputColor.rgb *= mix(compositeLight, vec3(1), isMaterialUnlit(scene));
        outputColor.rgb = linearToSrgb(outputColor.rgb);

        if (isUnderwater) {
            sampleUnderwater(outputColor.rgb, waterType, waterDepth, scene.sun.ndl);
        }
    }

    outputColor.rgb = clamp(outputColor.rgb, 0, 1);

    // Skip unnecessary color conversion if possible
    if (saturation != 1 || contrast != 1) {
        vec3 hsv = srgbToHsv(outputColor.rgb);

        // Apply saturation setting
        hsv.y *= saturation;

        // Apply contrast setting
        if (hsv.z > 0.5) {
            hsv.z = 0.5 + ((hsv.z - 0.5) * contrast);
        } else {
            hsv.z = 0.5 - ((0.5 - hsv.z) * contrast);
        }

        outputColor.rgb = hsvToSrgb(hsv);
    }

    outputColor.rgb = colorBlindnessCompensation(outputColor.rgb);

    // apply fog
    if (!isUnderwater) {
        // ground fog
        float distance = distance(IN.position, cameraPos);
        float closeFadeDistance = 1500;
        float groundFog = 1.0 - clamp((IN.position.y - groundFogStart) / (groundFogEnd - groundFogStart), 0.0, 1.0);
        groundFog = mix(0.0, groundFogOpacity, groundFog);
        groundFog *= clamp(distance / closeFadeDistance, 0.0, 1.0);

        // multiply the visibility of each fog
        float combinedFog = 1 - (1 - IN.fogAmount) * (1 - groundFog);

        if (isWater) {
            outputColor.a = combinedFog + outputColor.a * (1 - combinedFog);
        }

        outputColor.rgb = mix(outputColor.rgb, fogColor, combinedFog);
    }

    FragColor = outputColor;
}
