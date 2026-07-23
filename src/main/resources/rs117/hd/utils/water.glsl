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
#include <uniforms/global.glsl>
#include <uniforms/materials.glsl>
#include <uniforms/water_types.glsl>

#include <utils/lights.glsl>
#include <utils/misc.glsl>

// pcg2d, from Jarzynski & Olano 2020: https://jcgt.org/published/0009/03/02/
vec2 hash22(vec2 p) {
    uvec2 v = uvec2(ivec2(floor(p)));
    v = v * 1664525u + 1013904223u;
    v.x += v.y * 1664525u; v.y += v.x * 1664525u;
    v ^= v >> 16u;
    v.x += v.y * 1664525u; v.y += v.x * 1664525u;
    v ^= v >> 16u;
    return vec2(v) * (1.0 / float(0xffffffffu));
}

// Triangle-grid stochastic detiling, Heitz & Neyret 2018:
// https://eheitzresearch.wordpress.com/722-2/
//
// anchorUv must be stable across animationFrame wraps (unscrolled world UV)
// so scroll wraps don't jump cell coords; lookupUv carries scroll + flow.
vec3 sampleNormalDetiled(int layer, vec2 anchorUv, vec2 lookupUv) {
    const mat2 gridToSkewed = mat2(1.0, 0.0, -0.57735, 1.15470);
    vec2 skewed = gridToSkewed * anchorUv * 3.4641016; // 2*sqrt(3)
    vec2 base = floor(skewed);
    vec2 f = skewed - base;
    float fz = 1.0 - f.x - f.y;

    vec2 v1, v2, v3;
    vec3 w;
    if (fz > 0.0) {
        v1 = base;
        v2 = base + vec2(0, 1);
        v3 = base + vec2(1, 0);
        w = vec3(fz, f.y, f.x);
    } else {
        v1 = base + vec2(1, 1);
        v2 = base + vec2(1, 0);
        v3 = base + vec2(0, 1);
        w = vec3(-fz, 1.0 - f.y, 1.0 - f.x);
    }

    vec2 uv1 = lookupUv + hash22(v1);
    vec2 uv2 = lookupUv + hash22(v2);
    vec2 uv3 = lookupUv + hash22(v3);
    vec2 dx = dFdx(lookupUv), dy = dFdy(lookupUv);

    vec3 s1 = textureGrad(textureArray, vec3(uv1, layer), dx, dy).xyz;
    vec3 s2 = textureGrad(textureArray, vec3(uv2, layer), dx, dy).xyz;
    vec3 s3 = textureGrad(textureArray, vec3(uv3, layer), dx, dy).xyz;
    vec3 blended = linearToSrgb(s1 * w.x + s2 * w.y + s3 * w.z);
    // Heitz/Neyret variance rescale, skipping T/T⁻¹ since tangent-space
    // normal maps sit near a known analytical mean.
    const vec3 flatMean = vec3(0.5, 0.5, 1.0);
    return flatMean + (blended - flatMean) / length(w);
}

vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);

    vec2 uv1 = worldUvs(3).yx - animationFrame(28 * waterType.duration);
    vec2 uv2 = worldUvs(3) + animationFrame(24 * waterType.duration);
    vec2 uv3 = IN.uv;

    vec2 flowMapUv = worldUvs(15) + animationFrame(50 * waterType.duration);
    float flowMapStrength = 0.025;

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;

    // get diffuse textures
    vec3 n1 = sampleNormalDetiled(waterType.normalMap, worldUvs(3).yx, uv1);
    vec3 n2 = sampleNormalDetiled(waterType.normalMap, worldUvs(3), uv2);
    float foamMask = texture(textureArray, vec3(uv3, MAT_WATER_FOAM.colorMap)).r;

    // normals
    n1 = -vec3((n1.x * 2 - 1) * waterType.normalStrength, n1.z, (n1.y * 2 - 1) * waterType.normalStrength);
    n2 = -vec3((n2.x * 2 - 1) * waterType.normalStrength, n2.z, (n2.y * 2 - 1) * waterType.normalStrength);
    vec3 normals = normalize(n1 + n2);

    float lightDotNormals = dot(normals, lightDir);
    float downDotNormals = -normals.y;
    float viewDotNormals = dot(viewDir, normals);

    vec2 distortion = uvFlow * .00075;
    float shadow = sampleShadowMap(IN.position, distortion, lightDotNormals);
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
    vec3 lightReflectDir = reflect(-lightDir, normals);
    vec3 lightSpecularOut = lightColor * specular(IN.texBlend, viewDir, lightReflectDir, vSpecularGloss, vSpecularStrength);

    // point lights
    vec3 pointLightsOut = vec3(0);
    vec3 pointLightsSpecularOut = vec3(0);
    calculateLighting(IN.position, normals, viewDir, IN.texBlend, vSpecularGloss, vSpecularStrength, pointLightsOut, pointLightsSpecularOut);

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
    if (waterType.fresnelAmount == 0.85)
        baseColor *= .75f; // Sailing hack
    float shoreLineMask = 1 - dot(IN.texBlend, (fAlphaBiasHsl & 127) / 127.f);
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

    float alpha = max(waterType.baseOpacity, max(foamAmount, max(finalFresnel, length(specularComposite / 3))));

    if (waterType.isFlat) {
        baseColor = mix(waterType.depthColor, baseColor, alpha);
        alpha = 1;
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

        causticsUv *= .75;

        const ivec2 direction = ivec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(17) * direction;
        vec2 flow2 = causticsUv * 1.5 + animationFrame(23) * -direction;
        vec3 caustics = sampleCaustics(flow1, flow2, .005);

        vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
        outputColor.rgb *= 1 + caustics * causticsColor * depthMultiplier * lightDotNormals * lightStrength;
    }
}
