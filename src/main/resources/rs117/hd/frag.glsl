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

#include uniforms/camera.glsl
#include uniforms/materials.glsl
#include uniforms/water_types.glsl
#include uniforms/lights.glsl

#include MATERIAL_CONSTANTS

uniform sampler2DArray textureArray;
uniform sampler2D shadowMap;

uniform mat4 lightProjectionMatrix;
uniform float elapsedTime;
uniform float colorBlindnessIntensity;
uniform vec4 fogColor;
uniform int fogDepth;
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
uniform vec3 lightDirection;
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

in FragmentData {
    vec3 position;
    vec3 normal;
    vec3 texBlend;
    float fogAmount;
} IN;

out vec4 FragColor;

vec2 worldUvs(float scale) {
    vec2 uv = IN.position.xz / (128 * scale);
    return vec2(uv.x, -uv.y);
}

#include utils/polyfills.glsl
#include utils/constants.glsl
#include utils/misc.glsl
#include utils/color_blindness.glsl
#include utils/caustics.glsl
#include utils/color_conversion.glsl
#include utils/normals.glsl
#include utils/specular.glsl
#include utils/displacement.glsl
#include utils/shadows.glsl
#include utils/water.glsl

void main() {
    vec3 camPos = vec3(cameraX, cameraY, cameraZ);
    vec3 downDir = vec3(0, -1, 0);
    // View & light directions are from the fragment to the camera/light
    vec3 viewDir = normalize(camPos - IN.position);
    vec3 lightDir = -lightDirection;

    Material material1 = getMaterial(vMaterialData[0] >> MATERIAL_INDEX_SHIFT);
    Material material2 = getMaterial(vMaterialData[1] >> MATERIAL_INDEX_SHIFT);
    Material material3 = getMaterial(vMaterialData[2] >> MATERIAL_INDEX_SHIFT);

    // Water data
    bool isTerrain = (vTerrainData[0] & 1) != 0; // 1 = 0b1
    int waterDepth1 = vTerrainData[0] >> 8;
    int waterDepth2 = vTerrainData[1] >> 8;
    int waterDepth3 = vTerrainData[2] >> 8;
    float waterDepth =
        waterDepth1 * IN.texBlend.x +
        waterDepth2 * IN.texBlend.y +
        waterDepth3 * IN.texBlend.z;
    int waterTypeIndex = isTerrain ? vTerrainData[0] >> 3 & 0x1F : 0;
    WaterType waterType = getWaterType(waterTypeIndex);

    // set initial texture map ids
    int colorMap1 = material1.colorMap;
    int colorMap2 = material2.colorMap;
    int colorMap3 = material3.colorMap;

    // only use one flowMap map
    int flowMap = material1.flowMap;

    bool isUnderwater = waterDepth != 0;
    bool isWater = waterTypeIndex > 0 && !isUnderwater;

    vec4 outputColor = vec4(1);

    if (isWater) {
        outputColor = sampleWater(waterTypeIndex, viewDir);
    } else {
        // Source: https://www.geeks3d.com/20130122/normal-mapping-without-precomputed-tangent-space-vectors/
        vec3 N = IN.normal;
        vec3 C1 = cross(vec3(0, 0, 1), N);
        vec3 C2 = cross(vec3(0, 1, 0), N);
        vec3 T = normalize(length(C1) > length(C2) ? C1 : C2);
        vec3 B = cross(N, T);
        mat3 TBN = mat3(T, B, N);

        vec2 uv1 = vUv[0].xy;
        vec2 uv2 = vUv[1].xy;
        vec2 uv3 = vUv[2].xy;
        vec2 blendedUv = uv1 * IN.texBlend.x + uv2 * IN.texBlend.y + uv3 * IN.texBlend.z;
        uv1 = uv2 = uv3 = blendedUv;

        // Scroll UVs
        uv1 += material1.scrollDuration * elapsedTime;
        uv2 += material2.scrollDuration * elapsedTime;
        uv3 += material3.scrollDuration * elapsedTime;

        // Scale from the center
        uv1 = (uv1 - .5) / material1.textureScale + .5;
        uv2 = (uv2 - .5) / material2.textureScale + .5;
        uv3 = (uv3 - .5) / material3.textureScale + .5;

        float selfShadowing = 0;
        vec3 fragPos = IN.position;
        #if PARALLAX_MAPPING
        mat3 invTBN = transpose(TBN);
        vec3 tangentViewDir = invTBN * viewDir;
        vec3 tangentLightDir = invTBN * lightDir;

        vec2 fragDelta = vec2(0);

        sampleDisplacementMap(material1, tangentViewDir, tangentLightDir, uv1, fragDelta, selfShadowing);
        sampleDisplacementMap(material2, tangentViewDir, tangentLightDir, uv2, fragDelta, selfShadowing);
        sampleDisplacementMap(material3, tangentViewDir, tangentLightDir, uv3, fragDelta, selfShadowing);

        // Average
        fragDelta /= 3;
        selfShadowing /= 3;

        fragPos += TBN * vec3(fragDelta, 0);
        #endif

        // get flowMap map
        vec2 flowMapUv = uv1 - animationFrame(material1.flowMapDuration);
        float flowMapStrength = material1.flowMapStrength;
        if (isUnderwater)
        {
            // Distort underwater textures
            flowMapUv = worldUvs(1.5) + animationFrame(10 * waterType.duration) * vec2(1, -1);
            flowMapStrength = 0.075;
        }

        vec2 uvFlow = texture(textureArray, vec3(flowMapUv, flowMap)).xy;
        uv1 += uvFlow * flowMapStrength;
        uv2 += uvFlow * flowMapStrength;
        uv3 += uvFlow * flowMapStrength;

        // get vertex colors
        vec4 flatColor = vec4(0.5, 0.5, 0.5, 1.0);
        vec4 baseColor1 = vColor[0];
        vec4 baseColor2 = vColor[1];
        vec4 baseColor3 = vColor[2];

        #if VANILLA_COLOR_BANDING
        vec4 baseColor =
            IN.texBlend[0] * baseColor1 +
            IN.texBlend[1] * baseColor2 +
            IN.texBlend[2] * baseColor3;

        baseColor.rgb = linearToSrgb(baseColor.rgb);
        baseColor.rgb = rgbToHsv(baseColor.rgb);
        baseColor.b = floor(baseColor.b * 127) / 127;
        baseColor.rgb = hsvToRgb(baseColor.rgb);
        baseColor.rgb = srgbToLinear(baseColor.rgb);

        baseColor1 = baseColor2 = baseColor3 = baseColor;
        #endif

        // get diffuse textures
        vec4 texColor1 = colorMap1 == -1 ? vec4(1) : texture(textureArray, vec3(uv1, colorMap1));
        vec4 texColor2 = colorMap2 == -1 ? vec4(1) : texture(textureArray, vec3(uv2, colorMap2));
        vec4 texColor3 = colorMap3 == -1 ? vec4(1) : texture(textureArray, vec3(uv3, colorMap3));
        texColor1.rgb *= material1.brightness;
        texColor2.rgb *= material2.brightness;
        texColor3.rgb *= material3.brightness;

        ivec3 isOverlay = ivec3(
            vMaterialData[0] >> MATERIAL_FLAG_IS_OVERLAY & 1,
            vMaterialData[1] >> MATERIAL_FLAG_IS_OVERLAY & 1,
            vMaterialData[2] >> MATERIAL_FLAG_IS_OVERLAY & 1
        );
        int overlayCount = isOverlay[0] + isOverlay[1] + isOverlay[2];
        ivec3 isUnderlay = ivec3(1) - isOverlay;
        int underlayCount = isUnderlay[0] + isUnderlay[1] + isUnderlay[2];

        // calculate blend amounts for overlay and underlay vertices
        vec3 underlayBlend = IN.texBlend * isUnderlay;
        vec3 overlayBlend = IN.texBlend * isOverlay;

        if (underlayCount == 0 || overlayCount == 0)
        {
            // if a tile has all overlay or underlay vertices,
            // use the default blend

            underlayBlend = IN.texBlend;
            overlayBlend = IN.texBlend;
        }
        else
        {
            // if there's a mix of overlay and underlay vertices,
            // calculate custom blends for each 'layer'

            float underlayBlendMultiplier = 1.0 / (underlayBlend[0] + underlayBlend[1] + underlayBlend[2]);
            // adjust back to 1.0 total
            underlayBlend *= underlayBlendMultiplier;

            float overlayBlendMultiplier = 1.0 / (overlayBlend[0] + overlayBlend[1] + overlayBlend[2]);
            // adjust back to 1.0 total
            overlayBlend *= overlayBlendMultiplier;
        }


        // get fragment colors by combining vertex colors and texture samples
        vec4 texA = material1.overrideBaseColor ? texColor1 : vec4(texColor1.rgb * baseColor1.rgb, min(texColor1.a, baseColor1.a));
        vec4 texB = material2.overrideBaseColor ? texColor2 : vec4(texColor2.rgb * baseColor2.rgb, min(texColor2.a, baseColor2.a));
        vec4 texC = material3.overrideBaseColor ? texColor3 : vec4(texColor3.rgb * baseColor3.rgb, min(texColor3.a, baseColor3.a));

        // combine fragment colors based on each blend, creating
        // one color for each overlay/underlay 'layer'
        vec4 underlayColor = texA * underlayBlend.x + texB * underlayBlend.y + texC * underlayBlend.z;
        vec4 overlayColor = texA * overlayBlend.x + texB * overlayBlend.y + texC * overlayBlend.z;

        float overlayMix = 0;

        if (overlayCount > 0 && underlayCount > 0)
        {
            // custom blending logic for blending overlays into underlays
            // in a style similar to 2008+ HD

            // fragment UV
            vec2 fragUv = blendedUv;
            // standalone UV
            // e.g. if there are 2 overlays and 1 underlay, the underlay is the standalone
            vec2 sUv[3];
            bool inverted = false;

            ivec3 isPrimary = isUnderlay;
            if (overlayCount == 1) {
                isPrimary = isOverlay;
                // we use this at the end of this logic to invert
                // the result if there's 1 overlay, 2 underlay
                // vs the default result from 1 underlay, 2 overlay
                inverted = true;
            }

            if (isPrimary[0] == 1) {
                sUv = vec2[](vUv[0].xy, vUv[1].xy, vUv[2].xy);
            } else if (isPrimary[1] == 1) {
                sUv = vec2[](vUv[1].xy, vUv[0].xy, vUv[2].xy);
            } else {
                sUv = vec2[](vUv[2].xy, vUv[0].xy, vUv[1].xy);
            }

            // point on side perpendicular to sUv[0]
            vec2 oppositePoint = sUv[1] + pointToLine(sUv[1], sUv[2], sUv[0]) * (sUv[2] - sUv[1]);

            // calculate position of fragment's UV relative to
            // line between sUv[0] and oppositePoint
            float result = pointToLine(sUv[0], oppositePoint, fragUv);

            if (inverted)
            {
                result = 1 - result;
            }

            result = clamp(result, 0, 1);

            float distance = distance(sUv[0], oppositePoint);

            float cutoff = 0.5;

            result = (result - (1.0 - cutoff)) * (1.0 / cutoff);
            result = clamp(result, 0, 1);

            float maxDistance = 2.5;
            if (distance > maxDistance)
            {
                float multi = distance / maxDistance;
                result = 1.0 - ((1.0 - result) * multi);
                result = clamp(result, 0, 1);
            }

            overlayMix = result;
        }

        outputColor = mix(underlayColor, overlayColor, overlayMix);

        // normals
        vec3 n1 = sampleNormalMap(material1, uv1, IN.normal, TBN);
        vec3 n2 = sampleNormalMap(material2, uv2, IN.normal, TBN);
        vec3 n3 = sampleNormalMap(material3, uv3, IN.normal, TBN);
        vec3 normals = normalize(n1 * IN.texBlend.x + n2 * IN.texBlend.y + n3 * IN.texBlend.z);

        float lightDotNormals = dot(normals, lightDir);
        float downDotNormals = dot(downDir, normals);
        float viewDotNormals = dot(viewDir, normals);


        float shadow = 0;
        if ((vMaterialData[0] >> MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING & 1) == 0)
            shadow = sampleShadowMap(fragPos, waterTypeIndex, vec2(0), lightDotNormals);
        shadow = max(shadow, selfShadowing);
        float inverseShadow = 1 - shadow;



        // specular
        vec3 vSpecularGloss = vec3(material1.specularGloss, material2.specularGloss, material3.specularGloss);
        vec3 vSpecularStrength = vec3(material1.specularStrength, material2.specularStrength, material3.specularStrength);
        vSpecularStrength *= vec3(
            material1.roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(uv1, material1.roughnessMap)).r),
            material2.roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(uv2, material2.roughnessMap)).r),
            material3.roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(uv3, material3.roughnessMap)).r)
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


        // calculate lighting

        // ambient light
        vec3 ambientLightOut = ambientColor * ambientStrength;

        float aoFactor =
            IN.texBlend.x * (material1.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv1, material1.ambientOcclusionMap)).r) +
            IN.texBlend.y * (material2.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv2, material2.ambientOcclusionMap)).r) +
            IN.texBlend.z * (material3.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv3, material3.ambientOcclusionMap)).r);
        ambientLightOut *= aoFactor;

        // directional light
        vec3 dirLightColor = lightColor * lightStrength;

        // underwater caustics based on directional light
        if (underwaterCaustics && underwaterEnvironment) {
            float scale = 12.8;
            vec2 causticsUv = worldUvs(scale);

            // height offset
            causticsUv += lightDir.xy * IN.position.y / (128 * scale);

            const ivec2 direction = ivec2(1, -1);
            const int driftSpeed = 231;
            vec2 drift = animationFrame(231) * ivec2(1, -2);
            vec2 flow1 = causticsUv + animationFrame(19) * direction + drift;
            vec2 flow2 = causticsUv * 1.25 + animationFrame(37) * -direction + drift;

            vec3 caustics = sampleCaustics(flow1, flow2) * 2;

            vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
            dirLightColor += caustics * causticsColor * lightDotNormals * pow(lightStrength, 1.5);
        }

        // apply shadows
        dirLightColor *= inverseShadow;

        vec3 lightColor = dirLightColor;
        vec3 lightOut = max(lightDotNormals, 0.0) * lightColor;

        // directional light specular
        vec3 lightReflectDir = reflect(lightDirection, normals);
        vec3 lightSpecularOut = specular(viewDir, lightReflectDir, vSpecularGloss, vSpecularStrength, lightColor, lightStrength).rgb;

        // point lights
        vec3 pointLightsOut = vec3(0);
        vec3 pointLightsSpecularOut = vec3(0);
        for (int i = 0; i < pointLightsCount; i++)
        {
            vec3 pointLightPos = vec3(PointLightArray[i].position.x, PointLightArray[i].position.z, PointLightArray[i].position.y);
            float pointLightStrength = PointLightArray[i].strength;
            vec3 pointLightColor = PointLightArray[i].color * pointLightStrength;
            float pointLightSize = PointLightArray[i].size;
            float distanceToLightSource = length(pointLightPos - IN.position);
            vec3 pointLightDir = normalize(pointLightPos - IN.position);

            if (distanceToLightSource <= pointLightSize)
            {
                float pointLightDotNormals = dot(normals, pointLightDir);
                vec3 pointLightOut = pointLightColor * max(pointLightDotNormals, 0.0);

                float attenuation = pow(clamp(1 - (distanceToLightSource / pointLightSize), 0.0, 1.0), 2.0);
                pointLightOut *= attenuation;

                pointLightsOut += pointLightOut;

                vec3 pointLightReflectDir = reflect(-pointLightDir, normals);
                vec4 spec = specular(viewDir, pointLightReflectDir, vSpecularGloss, vSpecularStrength, pointLightColor, pointLightStrength) * attenuation;
                pointLightsSpecularOut += spec.rgb;
            }
        }


        // sky light
        vec3 skyLightColor = fogColor.rgb;
        float skyLightStrength = 0.5;
        float skyDotNormals = downDotNormals;
        vec3 skyLightOut = max(skyDotNormals, 0.0) * skyLightColor * skyLightStrength;


        // lightning
        vec3 lightningColor = vec3(.25, .25, .25);
        float lightningStrength = lightningBrightness;
        float lightningDotNormals = downDotNormals;
        vec3 lightningOut = max(lightningDotNormals, 0.0) * lightningColor * lightningStrength;


        // underglow
        vec3 underglowOut = underglowColor * max(normals.y, 0) * underglowStrength;


        // fresnel reflection
        float baseOpacity = 0.4;
        float fresnel = 1.0 - clamp(viewDotNormals, 0.0, 1.0);
        float finalFresnel = clamp(mix(baseOpacity, 1.0, fresnel * 1.2), 0.0, 1.0);
        vec3 surfaceColor = vec3(0);
        vec3 surfaceColorOut = surfaceColor * max(combinedSpecularStrength, 0.2);


        // apply lighting
        vec3 compositeLight = ambientLightOut + lightOut + lightSpecularOut + skyLightOut + lightningOut +
        underglowOut + pointLightsOut + pointLightsSpecularOut + surfaceColorOut;

        float unlit = dot(IN.texBlend, vec3(material1.unlit, material2.unlit, material3.unlit));
        outputColor.rgb *= mix(compositeLight, vec3(1), unlit);
        outputColor.rgb = linearToSrgb(outputColor.rgb);

        if (isUnderwater) {
            sampleUnderwater(outputColor.rgb, waterType, waterDepth, lightDotNormals);
        }
    }


    outputColor.rgb = clamp(outputColor.rgb, 0, 1);
    vec3 hsv = rgbToHsv(outputColor.rgb);

    // Apply saturation setting
    hsv.y *= saturation;

    // Apply contrast setting
    if (hsv.z > 0.5) {
        hsv.z = 0.5 + ((hsv.z - 0.5) * contrast);
    } else {
        hsv.z = 0.5 - ((0.5 - hsv.z) * contrast);
    }

    outputColor.rgb = hsvToRgb(hsv);
    outputColor.rgb = colorBlindnessCompensation(outputColor.rgb);

    // apply fog
    if (!isUnderwater) {
        // ground fog
        float distance = distance(IN.position, camPos);
        float closeFadeDistance = 1500;
        float groundFog = 1.0 - clamp((IN.position.y - groundFogStart) / (groundFogEnd - groundFogStart), 0.0, 1.0);
        groundFog = mix(0.0, groundFogOpacity, groundFog);
        groundFog *= clamp(distance / closeFadeDistance, 0.0, 1.0);

        // multiply the visibility of each fog
        float combinedFog = 1 - (1 - IN.fogAmount) * (1 - groundFog);

        if (isWater) {
            outputColor.a = combinedFog + outputColor.a * (1 - combinedFog);
        }

        outputColor.rgb = mix(outputColor.rgb, fogColor.rgb, combinedFog);
    }

    FragColor = outputColor;
}
