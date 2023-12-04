float sampleAmbientOcclusion(const Material mat, const vec2 uv) {
    if (mat.ambientOcclusionMap == -1)
        return 1;
    return texture(textureArray, vec3(uv, mat.ambientOcclusionMap)).r;
}

float getAmbientOcclusion(Scene scene) {
    return
        scene.texBlend[0] * sampleAmbientOcclusion(scene.materials[0], scene.uvs[0]) +
        scene.texBlend[1] * sampleAmbientOcclusion(scene.materials[1], scene.uvs[1]) +
        scene.texBlend[2] * sampleAmbientOcclusion(scene.materials[2], scene.uvs[2]);
}

vec3 ambientTerm(Scene scene, vec3 fogColor, float strength) {
    vec3 skyLight = max(scene.ddn, 0.0) * fogColor;
    vec3 ambientLight = (ambientColor + skyLight) * strength;
    return ambientLight * getAmbientOcclusion(scene);
}

// Generally in most engines, attenuation ends up being the shadowmap and the falloff, depending on the light type.
// So we're going to do the same thing here to make it easy to access / modify
float lightAttenuation(inout Light light, Scene scene, int flags) {
    float atten = 1.0f;

    switch(light.type) {
        case LIGHT_DIRECTIONAL:
            #if (DISABLE_DIRECTIONAL_SHADING)
                light.ndl = .7;
            #endif

            float shadow = 0;
            if ((flags >> MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING & 1) == 0) {
                shadow = sampleShadowMap(scene.fragPos, scene.waterTypeIndex, vec2(0), light.ndl);
            }

            atten = 1 - max(shadow, 0);
            break;
        case LIGHT_POINT:
            float falloff = 1 - sqrt(min(light.distanceSquared / light.radiusSquared, 1));
            atten = falloff * falloff;
            break;
    }

    return atten;
}

void gatherAdditiveLights(inout vec3 additiveLights, inout vec3 additiveLightsSpecular, Scene scene, int flags) {
    for (int i = 0; i < pointLightsCount; i++) {
        Light light;
        light.color = PointLightArray[i].color;
        light.position = PointLightArray[i].position.xyz;
        light.radiusSquared = PointLightArray[i].position.w;

        vec3 lightToFrag = light.position - scene.fragPos;
        light.distanceSquared = dot(lightToFrag, lightToFrag);

        if (light.distanceSquared <= light.radiusSquared) {
            light.type = LIGHT_POINT; // Hardcoding point light type here for now. We will need a better way to define light type in the future if we ever add spot lights or something.

            vec3 pointLightDir = normalize(light.position - scene.fragPos);
            populateLightVectors(light, pointLightDir, scene.normals);
            populateLightDotProducts(light, scene, scene.normals);

            float attenuation = lightAttenuation(light, scene, flags);

            additiveLights += light.color * light.ndl * attenuation;
            additiveLightsSpecular += light.color * specular(scene.viewDir, light.reflection, scene.smoothness, scene.reflectivity) * attenuation;
        }
    }
}
