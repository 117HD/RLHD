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

in float fogAmount;
flat in vec4 vColor1;
flat in vec4 vColor2;
flat in vec4 vColor3;
flat in vec2 vUv1;
flat in vec2 vUv2;
flat in vec2 vUv3;
in vec3 normals;
in vec3 position;
in vec3 texBlend;
flat in ivec3 materialId;
flat in ivec3 terrainData;
flat in ivec3 isOverlay;

out vec4 FragColor;

#include utils/color_blindness.glsl
#include utils/caustics.glsl
#include utils/color_conversion.glsl
#include utils/misc.glsl
#include utils/normals.glsl
#include utils/specular.glsl
#include utils/displacement.glsl

void main() {
    vec3 camPos = vec3(cameraX, cameraY, cameraZ);
    vec3 downDir = normalize(vec3(0, -1.0, 0));
    // View & light directions are from the fragment to the camera/light
    vec3 viewDir = normalize(camPos - position);
    vec3 lightDir = -lightDirection;

    // material data
    Material material1 = getMaterial(materialId.x);
    Material material2 = getMaterial(materialId.y);
    Material material3 = getMaterial(materialId.z);

    // water data
    bool isTerrain = (terrainData.x & 1) != 0; // 1 = 0b1
    int waterDepth1 = terrainData.x >> 8;
    int waterDepth2 = terrainData.y >> 8;
    int waterDepth3 = terrainData.z >> 8;
    float waterDepth =
        waterDepth1 * texBlend.x +
        waterDepth2 * texBlend.y +
        waterDepth3 * texBlend.z;
    int waterTypeIndex = isTerrain ? terrainData.x >> 3 & 0x1F : 0;
    WaterType waterType = getWaterType(waterTypeIndex);

    // set initial texture map ids
    int colorMap1 = material1.colorMap;
    int colorMap2 = material2.colorMap;
    int colorMap3 = material3.colorMap;

    // only use one flowMap map
    int flowMap = material1.flowMap;

    bool isUnderwater = waterDepth != 0;
    bool isWater = waterTypeIndex > 0 && !isUnderwater;

    if (isWater)
    {
        colorMap1 = waterType.normalMap; // wave normal map 1
        colorMap2 = waterType.normalMap; // wave normal map 2
        colorMap3 = waterType.foamMap; // foam diffuse map
        flowMap = isUnderwater ? waterType.underwaterFlowMap : waterType.flowMap; // wave flow map
    }

    float alpha = 1;
    vec4 fragColor = vColor1 * texBlend.x + vColor2 * texBlend.y + vColor3 * texBlend.z;

    // Source: https://www.geeks3d.com/20130122/normal-mapping-without-precomputed-tangent-space-vectors/
    vec3 N = normalize(normals);
    vec3 C1 = cross(vec3(0, 0, 1), N);
    vec3 C2 = cross(vec3(0, 1, 0), N);
    vec3 T = normalize(length(C1) > length(C2) ? C1 : C2);
    vec3 B = normalize(cross(N, T));
    mat3 TBN = mat3(T, B, N);

    vec2 blendedUv = vUv1 * texBlend.x + vUv2 * texBlend.y + vUv3 * texBlend.z;
    vec2 uv1 = blendedUv;
    vec2 uv2 = blendedUv;
    vec2 uv3 = blendedUv;

    // Scroll UVs
    uv1 += material1.scrollDuration * elapsedTime;
    uv2 += material2.scrollDuration * elapsedTime;
    uv3 += material3.scrollDuration * elapsedTime;

    // Scale from the center
    uv1 = (uv1 - .5) / material1.textureScale + .5;
    uv2 = (uv2 - .5) / material2.textureScale + .5;
    uv3 = (uv3 - .5) / material3.textureScale + .5;

    float selfShadowing = 0;
    vec3 fragPos = position;
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

    // water uvs
    if (isWater)
    {
        uv1 = vec2(-worldUvs(5).y + animationFrame(31 * waterType.duration),
        worldUvs(5).x + animationFrame(31 * waterType.duration));
        uv2 = vec2(worldUvs(3).y - animationFrame(24 * waterType.duration),
        worldUvs(3).x - animationFrame(24 * waterType.duration));
    }

    // get flowMap map
    vec2 flowMapUv = uv1 - animationFrame(material1.flowMapDuration);
    float flowMapStrength = material1.flowMapStrength;
    if (isWater)
    {
        flowMapUv = worldUvs(15) + animationFrame(50 * waterType.duration);
        flowMapStrength = 0.025;
    }
    if (isUnderwater)
    {
        flowMapUv = worldUvs(1.5) + animationFrame(10 * waterType.duration) * vec2(1, -1);
        flowMapStrength = 0.075;
    }

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, flowMap)).xy;
    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;
    if (isWater)
    {
        uv1 = vec2(worldUvs(2).x + animationFrame(20 * waterType.duration) + uvFlow.x * flowMapStrength,
        worldUvs(2).y + animationFrame(20 * waterType.duration) + uvFlow.y * flowMapStrength);
        uv1 = vec2(worldUvs(3).x - animationFrame(28 * waterType.duration) - uvFlow.x * flowMapStrength,
        worldUvs(3).y + animationFrame(28 * waterType.duration) + uvFlow.y * flowMapStrength);
    }

    // get vertex colors
    vec4 flatColor = vec4(0.5, 0.5, 0.5, 1.0);
    vec4 baseColor1 = vColor1;
    vec4 baseColor2 = vColor2;
    vec4 baseColor3 = vColor3;

    // get diffuse textures
    vec4 texColor1 = colorMap1 == -1 ? vec4(1) : texture(textureArray, vec3(uv1, colorMap1));
    vec4 texColor2 = colorMap2 == -1 ? vec4(1) : texture(textureArray, vec3(uv2, colorMap2));
    vec4 texColor3 = colorMap3 == -1 ? vec4(1) : texture(textureArray, vec3(uv3, colorMap3));

    ivec3 isOverlay = isOverlay;
    int overlayCount = isOverlay[0] + isOverlay[1] + isOverlay[2];
    ivec3 isUnderlay = ivec3(1) - isOverlay;
    int underlayCount = isUnderlay[0] + isUnderlay[1] + isUnderlay[2];

    // calculate blend amounts for overlay and underlay vertices
    vec3 underlayBlend = texBlend * isUnderlay;
    vec3 overlayBlend = texBlend * isOverlay;

    if (underlayCount == 0 || overlayCount == 0)
    {
        // if a tile has all overlay or underlay vertices,
        // use the default blend

        underlayBlend = texBlend;
        overlayBlend = texBlend;
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

    if (overlayCount == 0 || overlayCount == 3)
    {
        overlayMix = 0;
    }
    else
    {
        // custom blending logic for blending overlays into underlays
        // in a style similar to 2008+ HD

        // fragment UV
        vec2 fragUv = blendedUv;
        // standalone UV
        // e.g. if there are 2 overlays and 1 underlay, the underlay is the standalone
        vec2 uvA = vec2(-999);
        // opposite UV A
        vec2 uvB = vec2(-999);
        // opposite UV B
        vec2 uvC = vec2(-999);
        bool inverted = false;

        // assign standalone UV to uvA and others to uvB, uvC
        for (int i = 0; i < 3; i++)
        {
            vec2 uv = vUv1;

            if (i == 1)
            {
                uv = vUv2;
            }
            else if (i == 2)
            {
                uv = vUv3;
            }

            if ((isOverlay[i] == 1 && overlayCount == 1) || (isUnderlay[i] == 1 && underlayCount == 1))
            {
                // assign standalone vertex UV to uvA
                uvA = uv;

                if (overlayCount == 1)
                {
                    // we use this at the end of this logic to invert
                    // the result if there's 1 overlay, 2 underlay
                    // vs the default result from 1 underlay, 2 overlay
                    inverted = true;
                }
            }
            else
            {
                // assign opposite vertex UV to uvB or uvC
                if (uvB == vec2(-999))
                {
                    uvB = uv;
                }
                else
                {
                    uvC = uv;
                }
            }
        }

        // point on side perpendicular to uvA
        vec2 oppositePoint = uvB + pointToLine(uvB, uvC, uvA) * (uvC - uvB);

        // calculate position of fragment's UV relative to
        // line between uvA and oppositePoint
        float result = pointToLine(uvA, oppositePoint, fragUv);

        if (inverted)
        {
            result = 1 - result;
        }

        result = clamp(result, 0, 1);

        float distance = distance(uvA, oppositePoint);

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

    vec4 texColor = mix(underlayColor, overlayColor, overlayMix);
    alpha = texColor.a;

    // normals
    vec3 normals = normals;

    if (isWater)
    {
        vec3 n1 = -vec3((texColor1.x * 2 - 1) * waterType.normalStrength, texColor1.z, (texColor1.y * 2 - 1) * waterType.normalStrength);
        vec3 n2 = -vec3((texColor2.x * 2 - 1) * waterType.normalStrength, texColor2.z, (texColor2.y * 2 - 1) * waterType.normalStrength);
        normals = n1 + n2;
    }
    else
    {
        vec3 n1 = sampleNormalMap(material1, uv1, normals, TBN);
        vec3 n2 = sampleNormalMap(material2, uv2, normals, TBN);
        vec3 n3 = sampleNormalMap(material3, uv3, normals, TBN);
        normals = (n1 + n2 + n3) / 3;
    }
    normals = normalize(normals);


    float lightDotNormals = dot(normals, lightDir);
    float downDotNormals = dot(downDir, normals);
    float viewDotNormals = dot(viewDir, normals);


    // sample shadow map
    float shadow = 0.0;
    if (shadowsEnabled == 1)
    {
        vec4 shadowPos = lightProjectionMatrix * vec4(fragPos, 1);
        vec3 projCoords = shadowPos.xyz / shadowPos.w * 0.5 + 0.5;
        if (isWater || isUnderwater)
        {
            projCoords += vec3(uvFlow * 0.00075, 0.0);
        }
        float currentDepth = projCoords.z;
        float shadowMinBias = 0.0005f;
        float shadowBias = max(shadowMaxBias * (1.0 - lightDotNormals), shadowMinBias);
        vec2 texelSize = 1.0 / textureSize(shadowMap, 0);
        for(int x = -1; x <= 1; ++x)
        {
            for(int y = -1; y <= 1; ++y)
            {
                float pcfDepth = texture(shadowMap, projCoords.xy + vec2(x, y) * texelSize).r;
                shadow += currentDepth - shadowBias > pcfDepth ? 1.0 : 0.0;
            }
        }
        shadow /= 9;

        // fade out shadows near shadow texture edges
        float cutoff = 0.1;
        if (projCoords.x <= cutoff)
        {
            float amt = projCoords.x / cutoff;
            shadow = mix(0.0, shadow, amt);
        }
        if (projCoords.y <= cutoff)
        {
            float amt = projCoords.y / cutoff;
            shadow = mix(0.0, shadow, amt);
        }
        if (projCoords.x >= 1.0 - cutoff)
        {
            float amt = 1.0 - ((projCoords.x - (1.0 - cutoff)) / cutoff);
            shadow = mix(0.0, shadow, amt);
        }
        if (projCoords.y >= 1.0 - cutoff)
        {
            float amt = 1.0 - ((projCoords.y - (1.0 - cutoff)) / cutoff);
            shadow = mix(0.0, shadow, amt);
        }

        shadow = clamp(shadow, 0.0, 1.0);
        shadow = projCoords.z > 1.0 ? 0.0 : shadow;
    }

    shadow = max(shadow, selfShadowing);
    float inverseShadow = 1.0 - shadow;



    // specular
    vec3 vSpecularGloss, vSpecularStrength;
    float combinedSpecularStrength;
    if (isWater)
    {
        vSpecularStrength = vec3(waterType.specularStrength);
        vSpecularGloss = vec3(waterType.specularGloss);
        combinedSpecularStrength = waterType.specularStrength;
    }
    else
    {
        vSpecularGloss = vec3(material1.specularGloss, material2.specularGloss, material3.specularGloss);
        vSpecularStrength = vec3(material1.specularStrength, material2.specularStrength, material3.specularStrength);
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
        combinedSpecularStrength = dot(vSpecularStrength, texBlend);
    }


    // calculate lighting

    // ambient light
    vec3 ambientLightOut = ambientColor * ambientStrength;

    ambientLightOut *= (
        (material1.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv1, material1.ambientOcclusionMap)).r) +
        (material2.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv2, material2.ambientOcclusionMap)).r) +
        (material3.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv3, material3.ambientOcclusionMap)).r)
    ) / 3;

    // directional light
    vec3 dirLightColor = lightColor * lightStrength;

    // underwater caustics based on directional light
    if (underwaterCaustics && underwaterEnvironment) {
        float scale = 12.8;
        vec2 causticsUv = worldUvs(scale);

        // height offset
        causticsUv += lightDir.xy * position.y / (128 * scale);

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
        float distanceToLightSource = length(pointLightPos - position);
        vec3 pointLightDir = normalize(pointLightPos - position);

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
    vec3 lightningColor = vec3(1.0, 1.0, 1.0);
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
    if (isWater)
    {
        // add sky gradient
        if (finalFresnel < 0.5)
        {
            surfaceColor = mix(waterColorDark, waterColorMid, finalFresnel * 2);
        }
        else
        {
            surfaceColor = mix(waterColorMid, waterColorLight, (finalFresnel - 0.5) * 2);
        }
    }
    vec3 surfaceColorOut = surfaceColor * max(combinedSpecularStrength, 0.2);


    // apply lighting
    vec3 compositeLight = ambientLightOut + lightOut + lightSpecularOut + skyLightOut + lightningOut +
        underglowOut + pointLightsOut + pointLightsSpecularOut + surfaceColorOut;

    vec3 compositeColor = texColor.rgb;

    if (isWater)
    {
        vec3 baseColor = waterType.surfaceColor * compositeLight;
        baseColor = mix(baseColor, surfaceColor, waterType.fresnelAmount);
        float shadowDarken = 0.15;
        baseColor *= (1.0 - shadowDarken) + inverseShadow * shadowDarken;
        float maxFoamAmount = 0.8;
        float foamAmount = min(1.0 - fragColor.r, maxFoamAmount);
        float foamDistance = 0.7;
        vec3 foamColor = waterType.foamColor;
        foamColor = foamColor * texColor3.rgb * compositeLight;
        foamAmount = clamp(pow(1.0 - ((1.0 - foamAmount) / foamDistance), 3), 0.0, 1.0) * waterType.hasFoam;
        foamAmount *= foamColor.r;
        baseColor = mix(baseColor, foamColor, foamAmount);
        vec3 specularComposite = mix(lightSpecularOut, vec3(0.0), foamAmount);
        float flatFresnel = (1.0 - dot(viewDir, downDir)) * 1.0;
        finalFresnel = max(finalFresnel, flatFresnel);
        finalFresnel -= finalFresnel * shadow * 0.2;
        baseColor += pointLightsSpecularOut + lightSpecularOut / 3;
        alpha = max(waterType.baseOpacity, max(foamAmount, max(finalFresnel, length(specularComposite / 3))));
        compositeColor = baseColor;
    }
    else
    {
        float unlit = dot(texBlend, vec3(material1.unlit, material2.unlit, material3.unlit));
        compositeColor *= mix(compositeLight, vec3(1), unlit);
        compositeColor = linearToSrgb(compositeColor);
    }


    if (isUnderwater)
    {
        // underwater terrain
        float lowestColorLevel = 500.0;
        float midColorLevel = 150.0;
        float depth = waterDepth; // e.g. 200
        float surfaceLevel = position.y - waterDepth; // e.g. -1600

        vec3 mixed = vec3(1);

        if (depth < midColorLevel)
        {
            mixed = mix(compositeColor, compositeColor * waterType.depthColor, translateRange(0.0, midColorLevel, depth));
        }
        else if (depth < lowestColorLevel)
        {
            mixed = mix(compositeColor * waterType.depthColor, vec3(0.0), translateRange(midColorLevel, lowestColorLevel, depth));
        }
        else
        {
            mixed = vec3(0.0);
        }
        compositeColor = mixed;

        // caustics
        if (underwaterCaustics)
        {
            const float scale = 1.75;
            const float maxCausticsDepth = 128 * 4;

            vec2 causticsUv = worldUvs(scale);

            float depthMultiplier = (position.y - surfaceLevel - maxCausticsDepth) / -maxCausticsDepth;
            depthMultiplier *= depthMultiplier;

            // height offset
            causticsUv += lightDir.xy * position.y / (128 * scale);

            const ivec2 direction = ivec2(1, -2);
            vec2 flow1 = causticsUv + animationFrame(17) * direction;
            vec2 flow2 = causticsUv * 1.5 + animationFrame(23) * -direction;
            vec3 caustics = sampleCaustics(flow1, flow2, .005);

            vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
            compositeColor *= 1 + caustics * causticsColor * depthMultiplier * lightDotNormals * lightStrength;
        }
    }

    if (isWater && waterType.isFlat)
    {
        alpha = 1.0f;
    }


    vec3 hsv = rgbToHsv(compositeColor);

    // Apply saturation setting
    hsv.y *= saturation;

    // Apply contrast setting
    if (hsv.z > 0.5)
    {
        hsv.z = 0.5 + ((hsv.z - 0.5) * contrast);
    }
    else
    {
        hsv.z = 0.5 - ((0.5 - hsv.z) * contrast);
    }

    compositeColor = hsvToRgb(hsv);

    compositeColor = colorBlindnessCompensation(compositeColor);

    if (!isUnderwater)
    {
        // apply ground fog
        float distance = distance(position, camPos);
        float closeFadeDistance = 1500;
        float groundFog = 1.0 - clamp((position.y - groundFogStart) / (groundFogEnd - groundFogStart), 0.0, 1.0);
        groundFog = mix(0.0, groundFogOpacity, groundFog);
        groundFog *= clamp(distance / closeFadeDistance, 0.0, 1.0);
        if (isWater)
        {
            alpha = max(alpha, groundFog);
        }
        compositeColor = mix(compositeColor, fogColor.rgb, groundFog);
    }

    // apply distance fog
    compositeColor = mix(clamp(compositeColor, 0.0, 1.0), fogColor.rgb, fogAmount);

    FragColor = vec4(compositeColor, alpha);
}
