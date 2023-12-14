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
#pragma once
#include utils/misc.glsl
#include utils/helpers.glsl
#include utils/lighting.glsl

vec4 sampleWater(inout Context ctx) {
    WaterType waterType = getWaterType(ctx.waterTypeIndex);

    vec2 uv1 = worldUvs(3).yx - animationFrame(28 * waterType.duration);
    vec2 uv2 = worldUvs(3) + animationFrame(24 * waterType.duration);
    vec2 uv3 = ctx.uvs[3];

    vec2 flowMapUv = worldUvs(15) + animationFrame(50 * waterType.duration);
    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, waterType.flowMap)).xy;
    const float flowMapStrength = 0.025;
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
    ctx.normals = normalize(n1 + n2);
    populateContextDotProducts(ctx);
    populateLightVectors(ctx.sun, lightDir, ctx.normals);
    populateLightDotProducts(ctx.sun, ctx);

    ctx.smoothness = vec3(waterType.specularGloss);
    ctx.reflectivity = vec3(waterType.specularStrength);

    float sunAttenuation = lightAttenuation(ctx.sun, ctx, uvFlow * .00075);
    vec3 ambientTerm = ambientTerm(ctx, fogColor, ambientStrength);
    vec3 diffuseTerm = ctx.sun.ndl * ctx.sun.color * sunAttenuation;
    vec3 specularTerm = ctx.sun.color * getSpecular(ctx.viewDir, ctx.sun.reflection, ctx.smoothness, ctx.reflectivity) * sunAttenuation;

    // point lights
    vec3 lightsDiffuse = vec3(0);
    vec3 lightsSpecular = vec3(0);
    gatherLights(lightsDiffuse, lightsSpecular, ctx);

    // lightning
    vec3 lightningEffect = ctx.udn * vec3(.25) * lightningBrightness;

    // underglow
    vec3 underglowOut = underglowColor * max(ctx.normals.y, 0) * underglowStrength;

    // fresnel reflection
    float arbitraryFresnelIshValue = min(1.12 - 0.72 * ctx.vdn, 1.0);
    vec3 surfaceColor;

    // add sky gradient
    if (arbitraryFresnelIshValue < 0.5) {
        surfaceColor = mix(waterColorDark, waterColorMid, arbitraryFresnelIshValue * 2);
    } else {
        surfaceColor = mix(waterColorMid, waterColorLight, (arbitraryFresnelIshValue - 0.5) * 2);
    }

    vec3 surfaceColorOut = surfaceColor * max(waterType.specularStrength, 0.2);

    // apply lighting
    vec3 compositeLight =
        ambientTerm +
        diffuseTerm +
        specularTerm +
        lightsDiffuse +
        lightsSpecular +
        underglowOut +
        lightningEffect +
        surfaceColorOut;

    vec3 baseColor = waterType.surfaceColor * compositeLight;
    baseColor = mix(baseColor, surfaceColor, waterType.fresnelAmount);
    float shoreLineMask = 1 - dot(IN.texBlend, vec3(vColor[0].x, vColor[1].x, vColor[2].x));
    float foamAmount = min(shoreLineMask, 0.8);
    float foamDistance = 0.7;
    vec3 foamColor = waterType.foamColor;
    foamColor = foamColor * foamMask * compositeLight;
    foamAmount = waterType.hasFoam * clamp(pow(1 - (1 - foamAmount) / foamDistance, 3), 0, 1);
    foamAmount *= foamColor.r;
    baseColor = mix(baseColor, foamColor, foamAmount);
    vec3 specularComposite = mix(specularTerm, vec3(0.0), foamAmount);
    float flatFresnel = 1 + ctx.viewDir.y;
    arbitraryFresnelIshValue = max(arbitraryFresnelIshValue, flatFresnel);
    arbitraryFresnelIshValue *= mix(1, sunAttenuation, .2);
    baseColor += lightsSpecular + specularTerm / 3;

    float alpha = max(waterType.baseOpacity, max(foamAmount, max(arbitraryFresnelIshValue, length(specularComposite / 3))));

    if (waterType.isFlat) {
        baseColor = mix(waterType.depthColor, baseColor, alpha);
        alpha = 1;
    }

    return vec4(baseColor, alpha);
}

void sampleUnderwater(inout vec3 outputColor, Context ctx) {
    if (!ctx.isUnderwater)
        return;

    // underwater terrain
    float lowestColorLevel = 500;
    float midColorLevel = 150;
    float surfaceLevel = IN.position.y - ctx.waterDepth; // e.g. -1600

    if (ctx.waterDepth < midColorLevel) {
        outputColor *= mix(vec3(1), ctx.waterType.depthColor, translateRange(0, midColorLevel, ctx.waterDepth));
    } else if (ctx.waterDepth < lowestColorLevel) {
        outputColor *= mix(ctx.waterType.depthColor, vec3(0), translateRange(midColorLevel, lowestColorLevel, ctx.waterDepth));
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
        causticsUv += lightDir.xy * IN.position.y / (128 * scale);

        const ivec2 direction = ivec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(17) * direction;
        vec2 flow2 = causticsUv * 1.5 + animationFrame(23) * -direction;
        vec3 caustics = sampleCaustics(flow1, flow2, .005);

        vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
        outputColor.rgb *= 1 + caustics * causticsColor * depthMultiplier * ctx.sun.ndl * ctx.sun.brightness;
    }
}

void applyWaterCaustics(inout Context ctx, bool underwaterCaustics, bool underwaterEnvironment) {
    // underwater caustics based on directional light
    if (underwaterCaustics && underwaterEnvironment) {
        float scale = 12.8;
        vec2 causticsUv = worldUvs(scale);

        // height offset
        causticsUv += ctx.sun.direction.xy * IN.position.y / (128 * scale);

        const ivec2 direction = ivec2(1, -1);
        const int driftSpeed = 231;
        vec2 drift = animationFrame(231) * ivec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(19) * direction + drift;
        vec2 flow2 = causticsUv * 1.25 + animationFrame(37) * -direction + drift;

        vec3 caustics = sampleCaustics(flow1, flow2) * 2;

        vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
        ctx.sun.color += caustics * causticsColor * ctx.sun.ndl * pow(ctx.sun.brightness, 1.5);
    }
}
