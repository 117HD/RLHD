float getAmbientOcclusion(Scene scene) {
    return
        scene.texBlend.x * (scene.materials[0].ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(scene.uvs[0], scene.materials[0].ambientOcclusionMap)).r) +
        scene.texBlend.y * (scene.materials[1].ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(scene.uvs[1], scene.materials[1].ambientOcclusionMap)).r) +
        scene.texBlend.z * (scene.materials[2].ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(scene.uvs[2], scene.materials[2].ambientOcclusionMap)).r);
}

vec3 ambientTerm(Scene scene, vec3 fogColor, float strength) {
    vec3 skyLight = max(scene.ddn, 0.0) * fogColor;
    vec3 ambientLight = (ambientColor + skyLight) * strength;
    return ambientLight * getAmbientOcclusion(scene);
}

// Generally in most engines, attenuation ends up being the shadowmap and the falloff, depending on the light type.
// So we're going to do the same thing here to make it easy to access / modify
float lightAttenuation(inout Light light, Scene scene, int flags)
{
    float atten = 1.0;

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
            vec3 lightToFrag = light.position - scene.fragPos;
            float distanceSquared = dot(lightToFrag, lightToFrag);
            float radiusSquared = light.radius;
            float falloff = 1 - min(distanceSquared / radiusSquared, 1);

            atten = falloff * falloff;
        break;
    }

    return atten;
}

void gatherAdditiveLights(inout vec3 additiveLights, inout vec3 additiveLightsSpecular, Scene scene, int flags)
{
    // Note: I actually saw a slight performance decrease when checking against light radius to distance, so I've removed that.
    // branches in shaders are generally fine, as long as you're branching on something constant. If you branch on things that change from fragment to fragment, it can cause performance decreases.
    for (int i = 0; i < pointLightsCount; i++) {
        Light light;
        light.type = LIGHT_POINT; // Hardcoding point light type here for now. We will need a better way to define light type in the future if we ever add spot lights or something.
        light.color = PointLightArray[i].color;
        light.position = PointLightArray[i].position.xyz;
        light.radius = PointLightArray[i].position.w;
        light.color = light.color * lightAttenuation(light, scene, flags);

        vec3 pointLightDir = normalize(light.position - scene.fragPos);
        populateLightVectors(light, pointLightDir, scene.normals);
        populateLightDotProducts(light, scene, scene.normals);

        additiveLights += light.color * light.ndl;
        additiveLightsSpecular += light.color * specular(scene.viewDir, light.reflection, scene.smoothness, scene.reflectivity);
    }
}