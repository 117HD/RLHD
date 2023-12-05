#pragma once

#include utils/helpers.glsl

float sampleAmbientOcclusion(const Material mat, const vec2 uv) {
    if (mat.ambientOcclusionMap == -1)
        return 1;
    return texture(textureArray, vec3(uv, mat.ambientOcclusionMap)).r;
}

float getAmbientOcclusion(Context ctx) {
    return
        ctx.texBlend[0] * sampleAmbientOcclusion(ctx.materials[0], ctx.uvs[0]) +
        ctx.texBlend[1] * sampleAmbientOcclusion(ctx.materials[1], ctx.uvs[1]) +
        ctx.texBlend[2] * sampleAmbientOcclusion(ctx.materials[2], ctx.uvs[2]);
}

vec3 ambientTerm(Context ctx, vec3 fogColor, float strength) {
    vec3 ambient = ambientColor * strength;
    vec3 skyLight = ctx.udn * fogColor * 0.5;
    return ambient + skyLight;
}

// Generally in most engines, attenuation ends up being the shadowmap and the falloff, depending on the light type.
// So we're going to do the same thing here to make it easy to access / modify
float lightAttenuation(inout Light light, const Context ctx, const vec2 distortion) {
    float atten = 1.0f;

    switch(light.type) {
        case LIGHT_DIRECTIONAL:
            #if (DISABLE_DIRECTIONAL_SHADING)
                light.ndl = .7;
            #endif

            float shadow = 0;
            if ((ctx.materialData >> MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING & 1) == 0) {
                shadow = sampleShadowMap(ctx.fragPos, distortion, light.ndl);
            }

            atten = 1 - max(shadow, 0);
            break;
        case LIGHT_POINT:
            float falloff = 1 - sqrt(min(light.distance / light.radius, 1));
            atten = falloff * falloff;
            break;
    }

    return atten;
}

void gatherLights(inout vec3 diffuse, inout vec3 specular, const Context ctx) {
    for (int i = 0; i < pointLightsCount; i++) {
        Light light;
        light.position = PointLightArray[i].position.xyz;
        light.radius = PointLightArray[i].position.w;

        vec3 lightToFrag = light.position - ctx.fragPos;
        light.distance = dot(lightToFrag, lightToFrag); // compute distance squared

        if (light.distance <= light.radius) {
            // Hardcoding point light type here for now.
            // We will need a better way to define light type in the future if we ever add spot lights or something.
            light.type = LIGHT_POINT;
            light.color = PointLightArray[i].color;

            vec3 pointLightDir = normalize(lightToFrag);
            populateLightVectors(light, pointLightDir, ctx.normals);
            populateLightDotProducts(light, ctx);

            float attenuation = lightAttenuation(light, ctx, vec2(0));

            diffuse += light.color * attenuation * light.ndl;
            specular += light.color * attenuation * getSpecular(ctx.viewDir, light.reflection, ctx.smoothness, ctx.reflectivity);
        }
    }
}
