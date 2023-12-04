vec3 ambientTerm(Scene scene, vec3 fogColor, float strength) {
    vec3 skyLight = max(scene.ddn, 0.0) * fogColor;
    vec3 ambientLight = (ambientColor + skyLight) * strength;
    return ambientLight * getAmbientOcclusion(scene);
}

vec3 diffuseTerm() {
    return vec3(0);
}

vec3 specularTerm() {
    return vec3(0);
}

// structured like this in case we ever do shadowmaps for other lights.
void applyShadowsToLight(inout Light light, Scene scene, int[3] vMaterialData, vec3 fragPos, int waterTypeIndex)
{
    // this is only here because its technically shadowing on the light.
    #if (DISABLE_DIRECTIONAL_SHADING)
        scene.sun.ndl = .7;
    #endif

    float shadow = 0;
    if ((vMaterialData[0] >> MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING & 1) == 0)
        shadow = sampleShadowMap(fragPos, waterTypeIndex, vec2(0), scene.sun.ndl);

    shadow = max(shadow, 0);
    float inverseShadow = 1 - shadow;
    light.color *= inverseShadow;
}

void gatherAdditiveLights(Scene scene, vec3 normals, vec3 vSpecularGloss, vec3 vSpecularStrength, inout vec3 additiveLights, inout vec3 additiveLightsSpecular)
{
    for (int i = 0; i < pointLightsCount; i++) {
        Light light;
        light.color = PointLightArray[i].color;

        vec4 pos = PointLightArray[i].position;
        vec3 lightToFrag = pos.xyz - IN.position;
        float distanceSquared = dot(lightToFrag, lightToFrag);
        float radiusSquared = pos.w;

        if (distanceSquared <= radiusSquared) {
            vec3 pointLightDir = normalize(lightToFrag);
            populateLightVectors(light, pointLightDir, normals);
            populateLightDotProducts(light, scene, normals);

            float attenuation = 1 - min(distanceSquared / radiusSquared, 1);
            light.color *= attenuation * attenuation;

            additiveLights += light.color * light.ndl;
            additiveLightsSpecular += light.color * specular(scene.viewDir, light.reflection, vSpecularGloss, vSpecularStrength);
        }
    }
}