/*
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
#include utils/misc.glsl

vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);

    vec2 baseUv = vUv[0].xy * IN.texBlend.x + vUv[1].xy * IN.texBlend.y + vUv[2].xy * IN.texBlend.z;
    vec2 uv3 = baseUv;

    vec2 uv2 = worldUvs(3) + vec2(-1, 1) * animationFrame(24 * waterType.duration);
    vec2 uv1 = worldUvs(3).yx + animationFrame(28 * waterType.duration);

    vec2 flowMapUv = worldUvs(15) + animationFrame(50 * waterType.duration);
    float flowMapStrength = 0.025;

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, waterType.flowMap)).xy;
    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;

    // get diffuse textures
    vec3 n1 = texture(textureArray, vec3(uv1, waterType.normalMap)).xyz;
    vec3 n2 = texture(textureArray, vec3(uv2, waterType.normalMap)).xyz;
    float foamMask = texture(textureArray, vec3(uv3, waterType.foamMap)).r;

    // normals
    n1 = -vec3((n1.x * 2 - 1) * waterType.normalStrength, n1.z, (n1.y * 2 - 1) * waterType.normalStrength);
    n2 = -vec3((n2.x * 2 - 1) * waterType.normalStrength, n2.z, (n2.y * 2 - 1) * waterType.normalStrength);
    vec3 normals = normalize(n1 + n2);

    float lightDotNormals = dot(normals, -lightDirection);
    float downDotNormals = -normals.y;
    float viewDotNormals = dot(viewDir, normals);

    vec2 distortion = uvFlow * .00075;
    float shadow = sampleShadowMap(IN.position, waterTypeIndex, distortion, lightDotNormals);
    float inverseShadow = 1 - shadow;

    vec3 vSpecularStrength = vec3(waterType.specularStrength);
    vec3 vSpecularGloss = vec3(waterType.specularGloss);
    float combinedSpecularStrength = waterType.specularStrength;

    // calculate lighting

    // ambient light
    vec3 ambientLightOut = ambientColor * ambientStrength;

    // directional light
    vec3 dirLightColor = lightColor * lightStrength;

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

    // add sky gradient
    if (finalFresnel < 0.5) {
        surfaceColor = mix(waterColorDark, waterColorMid, finalFresnel * 2);
    } else {
        surfaceColor = mix(waterColorMid, waterColorLight, (finalFresnel - 0.5) * 2);
    }

    vec3 surfaceColorOut = surfaceColor * max(combinedSpecularStrength, 0.2);


    // apply lighting
    vec3 compositeLight = ambientLightOut + lightOut + lightSpecularOut + skyLightOut + lightningOut +
    underglowOut + pointLightsOut + pointLightsSpecularOut + surfaceColorOut;

    vec3 baseColor = waterType.surfaceColor * compositeLight;
    baseColor = mix(baseColor, surfaceColor, waterType.fresnelAmount);
    float shoreLineMask = 1 - dot(IN.texBlend, vec3(vColor[0].x, vColor[1].x, vColor[2].x));
    float maxFoamAmount = 0.8;
    float foamAmount = min(shoreLineMask, maxFoamAmount);
    float foamDistance = 0.7;
    vec3 foamColor = waterType.foamColor;
    foamColor = foamColor * foamMask * compositeLight;
    foamAmount = clamp(pow(1.0 - ((1.0 - foamAmount) / foamDistance), 3), 0.0, 1.0) * waterType.hasFoam;
    foamAmount *= foamColor.r;
    baseColor = mix(baseColor, foamColor, foamAmount);
    vec3 specularComposite = mix(lightSpecularOut, vec3(0.0), foamAmount);
    float flatFresnel = (1.0 - dot(viewDir, vec3(0, -1, 0))) * 1.0;
    finalFresnel = max(finalFresnel, flatFresnel);
    finalFresnel -= finalFresnel * shadow * 0.2;
    baseColor += pointLightsSpecularOut + lightSpecularOut / 3;

    float alpha = 1;
    if (!waterType.isFlat) {
        alpha = max(waterType.baseOpacity, max(foamAmount, max(finalFresnel, length(specularComposite / 3))));
    }

    return vec4(baseColor, alpha);
}

void sampleUnderwater(inout vec3 outputColor, WaterType waterType, float depth, float lightDotNormals) {
    // underwater terrain
    float lowestColorLevel = 500;
    float midColorLevel = 150;
    float surfaceLevel = IN.position.y - depth; // e.g. -1600

    if (depth < midColorLevel) {
        outputColor *= mix(vec3(1), waterType.depthColor, translateRange(0, midColorLevel, depth));
    } else if (depth < lowestColorLevel) {
        outputColor *= mix(waterType.depthColor, vec3(0), translateRange(midColorLevel, lowestColorLevel, depth));
    } else {
        outputColor = vec3(0);
    }

    if (underwaterCaustics) {
        const float scale = 1.75;
        const float maxCausticsDepth = 128 * 4;

        vec2 causticsUv = worldUvs(scale);

        float depthMultiplier = (IN.position.y - surfaceLevel - maxCausticsDepth) / -maxCausticsDepth;
        depthMultiplier *= depthMultiplier;

        // height offset
        causticsUv += -lightDirection.xy * IN.position.y / (128 * scale);

        const ivec2 direction = ivec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(17) * direction;
        vec2 flow2 = causticsUv * 1.5 + animationFrame(23) * -direction;
        vec3 caustics = sampleCaustics(flow1, flow2, .005);

        vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
        outputColor.rgb *= 1 + caustics * causticsColor * depthMultiplier * lightDotNormals * lightStrength;
    }
}
