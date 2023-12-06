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
flat in vec2 vUv[3];
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
#include utils/structs.glsl
#include utils/misc.glsl
#include utils/color_blindness.glsl
#include utils/caustics.glsl
#include utils/color_utils.glsl
#include utils/normals.glsl
#include utils/specular.glsl
#include utils/displacement.glsl
#include utils/shadows.glsl
#include utils/water.glsl
#include utils/helpers.glsl
#include utils/lighting.glsl

void main() {
    Context ctx;
    ctx.viewDir = normalize(cameraPos - IN.position);
    ctx.fragPos = IN.position;
    ctx.texBlend = IN.texBlend;
    ctx.mipBias = 0;

    ctx.sun.type = LIGHT_DIRECTIONAL;
    ctx.sun.color = lightColor * lightStrength;
    ctx.sun.brightness = lightStrength;

    ctx.materialData = vMaterialData[0];
    ctx.materials[0] = getMaterial(vMaterialData[0] >> MATERIAL_INDEX_SHIFT);
    ctx.materials[1] = getMaterial(vMaterialData[1] >> MATERIAL_INDEX_SHIFT);
    ctx.materials[2] = getMaterial(vMaterialData[2] >> MATERIAL_INDEX_SHIFT);

    ctx.uvs[0] = vUv[0];
    ctx.uvs[1] = vUv[1];
    ctx.uvs[2] = vUv[2];
    ctx.uvs[3] =
        ctx.texBlend[0] * vUv[0] +
        ctx.texBlend[1] * vUv[1] +
        ctx.texBlend[2] * vUv[2];

    populateSceneTerrainInformation(ctx);

    vec4 outputColor = vec4(1);
    if (ctx.isWater) {
        outputColor = sampleWater(ctx);
    } else {
        populateUvs(ctx);
        applyUvFlow(ctx);
        populateTangentSpaceMatrix(ctx);
        applyUvDisplacement(ctx);
        populateAlbedo(ctx);
        populateNormals(ctx);
        populateSmoothnessAndReflectivity(ctx);
        populateContextDotProducts(ctx);
        populateLightVectors(ctx.sun, lightDir, ctx.normals);
        populateLightDotProducts(ctx.sun, ctx);
        applyWaterCaustics(ctx, underwaterCaustics, underwaterEnvironment);

        float sunAttenuation = lightAttenuation(ctx.sun, ctx, vec2(0));
        vec3 ambientTerm = ambientTerm(ctx, fogColor, ambientStrength);
        vec3 diffuseTerm = ctx.sun.ndl * ctx.sun.color * sunAttenuation;
        vec3 specularTerm = ctx.sun.color * getSpecular(ctx.viewDir, ctx.sun.reflection, ctx.smoothness, ctx.reflectivity) * sunAttenuation;

        vec3 lightsDiffuse = vec3(0);
        vec3 lightsSpecular = vec3(0);
        gatherLights(lightsDiffuse, lightsSpecular, ctx);

        vec3 lightningEffect = ctx.udn * vec3(.25) * lightningBrightness;

        vec3 underglow = underglowColor * max(ctx.normals.y, 0) * underglowStrength;

        vec3 compositeLight =
            ambientTerm +
            diffuseTerm +
            specularTerm +
            lightsDiffuse +
            lightsSpecular +
            underglow +
            lightningEffect;

        outputColor = ctx.albedo;
        outputColor.rgb *= mix(compositeLight, vec3(1), isMaterialUnlit(ctx));
        outputColor.rgb = linearToSrgb(outputColor.rgb);

        sampleUnderwater(outputColor.rgb, ctx);
    }

    postProcessImage(outputColor.rgb);
    applyFog(outputColor, ctx, IN.position, cameraPos, groundFogStart, groundFogEnd, groundFogOpacity);

    FragColor = outputColor;
}
