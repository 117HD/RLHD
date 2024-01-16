#pragma once

#include utils/helpers.glsl

float sampleAmbientOcclusion(const Material mat, const vec2 uv) {
    if (mat.ambientOcclusionMap == -1)
        return 1.f;
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

        light.radius *= 5;

        vec3 c = light.position;
        float r = sqrt(light.radius);
        vec3 o = ctx.fragPos;
        vec3 u = ctx.viewDir;
        float udotomc = dot(-ctx.viewDir, o - c);
        float nabla = udotomc * udotomc - dot(o - c, o - c) + light.radius;

        if (nabla > 0) {
            nabla = sqrt(nabla);
            float t1 = max(0, -udotomc - nabla);
            float t2 = max(0, -udotomc + nabla);
            float d = 2 * nabla;

//        if (light.distance <= light.radius) {
            // Hardcoding point light type here for now.
            // We will need a better way to define light type in the future if we ever add spot lights or something.
            light.type = LIGHT_POINT;
            light.color = PointLightArray[i].color;

            vec3 pointLightDir = normalize(lightToFrag);
            populateLightVectors(light, pointLightDir, ctx.normals);
            populateLightDotProducts(light, ctx);

            float a = sqrt(-udotomc * udotomc - 2 * dot(o, c) + dot(o, o) + dot(c, c));
            a *= 128 * 4 * PI;
            a = clamp(a, 100, 500000);
            float integral = (atan(t2 + udotomc, a) - atan(t1 + udotomc, a)) / a;

            float attenuation = lightAttenuation(light, ctx, vec2(0));
            specular += light.color * attenuation * getSpecular(ctx.viewDir, light.reflection, ctx.smoothness, ctx.reflectivity);

            attenuation = integral * 5000000;

            diffuse += light.color * attenuation;// * light.ndl;
        }
    }
}
