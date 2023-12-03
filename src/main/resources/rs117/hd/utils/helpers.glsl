void populateLightVectors(inout Light light, vec3 dir, vec3 normals) {
    light.direction = dir;
    light.reflection = reflect(-light.direction, normals);
}

void populateLightDotProducts(inout Light light, Scene scene, vec3 normals) {
    light.ndl = max(dot(normals, light.direction), 0);
}

void populateSceneDotProducts(inout Scene scene, vec3 normals) {
    scene.vdn = dot(scene.viewDir, normals);
    scene.ddn = dot(scene.downDir, normals);
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

vec3 adjustFragPos(vec3 pos) {
    vec3 fragPos = pos;
    #if PARALLAX_OCCLUSION_MAPPING
        mat3 invTBN = inverse(TBN);
        vec3 tsViewDir = invTBN * viewDir;
        vec3 tsLightDir = invTBN * -lightDir;

        vec3 fragDelta = vec3(0);

        sampleDisplacementMap(material1, tsViewDir, tsLightDir, uv1, fragDelta, selfShadowing);
        sampleDisplacementMap(material2, tsViewDir, tsLightDir, uv2, fragDelta, selfShadowing);
        sampleDisplacementMap(material3, tsViewDir, tsLightDir, uv3, fragDelta, selfShadowing);

        // Average
        fragDelta /= 3;
        selfShadowing /= 3;

        fragPos += TBN * fragDelta;
    #endif
    return fragPos;
}

void adjustVertexColors(inout vec4 baseColor1, inout vec4 baseColor2, inout vec4 baseColor3) {
    #if VANILLA_COLOR_BANDING
        vec4 baseColor =
            IN.texBlend[0] * baseColor1 +
            IN.texBlend[1] * baseColor2 +
            IN.texBlend[2] * baseColor3;

        baseColor.rgb = linearToSrgb(baseColor.rgb);
        baseColor.rgb = srgbToHsv(baseColor.rgb);
        baseColor.b = floor(baseColor.b * 127) / 127;
        baseColor.rgb = hsvToSrgb(baseColor.rgb);
        baseColor.rgb = srgbToLinear(baseColor.rgb);

        baseColor1 = baseColor2 = baseColor3 = baseColor;
    #endif
}

// wow this is complicated. someone who understands this blending can refactor this if they want. I'll just move it to tidy things up for now.
void getOverlayUnderlayColorBlend(int[3] vMaterialData, vec2 blendedUv, vec3 texBlend, vec4 texA, vec4 texB, vec4 texC, inout vec4 overlayColor, inout vec4 underlayColor, inout float overlayMix) {
    ivec3 isOverlay = ivec3(
        vMaterialData[0] >> MATERIAL_FLAG_IS_OVERLAY & 1,
        vMaterialData[1] >> MATERIAL_FLAG_IS_OVERLAY & 1,
        vMaterialData[2] >> MATERIAL_FLAG_IS_OVERLAY & 1
    );
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

        underlayBlend = IN.texBlend;
        overlayBlend = IN.texBlend;
    }
    else
    {
        // if there's a mix of overlay and underlay vertices,
        // calculate custom blends for each 'layer'

        float underlayBlendMultiplier = 1.0 / (underlayBlend[0] + underlayBlend[1] + underlayBlend[2]);
        // adjust back to 1.0 total
        underlayBlend *= underlayBlendMultiplier;
        underlayBlend = clamp(underlayBlend, 0, 1);

        float overlayBlendMultiplier = 1.0 / (overlayBlend[0] + overlayBlend[1] + overlayBlend[2]);
        // adjust back to 1.0 total
        overlayBlend *= overlayBlendMultiplier;
        overlayBlend = clamp(overlayBlend, 0, 1);
    }

    // combine fragment colors based on each blend, creating
    // one color for each overlay/underlay 'layer'
    underlayColor = texA * underlayBlend.x + texB * underlayBlend.y + texC * underlayBlend.z;
    overlayColor = texA * overlayBlend.x + texB * overlayBlend.y + texC * overlayBlend.z;

    if (overlayCount > 0 && underlayCount > 0)
    {
        // custom blending logic for blending overlays into underlays
        // in a style similar to 2008+ HD

        // fragment UV
        vec2 fragUv = blendedUv;
        // standalone UV
        // e.g. if there are 2 overlays and 1 underlay, the underlay is the standalone
        vec2 sUv[3];
        bool inverted = false;

        ivec3 isPrimary = isUnderlay;
        if (overlayCount == 1) {
            isPrimary = isOverlay;
            // we use this at the end of this logic to invert
            // the result if there's 1 overlay, 2 underlay
            // vs the default result from 1 underlay, 2 overlay
            inverted = true;
        }

        if (isPrimary[0] == 1) {
            sUv = vec2[](vUv[0].xy, vUv[1].xy, vUv[2].xy);
        } else if (isPrimary[1] == 1) {
            sUv = vec2[](vUv[1].xy, vUv[0].xy, vUv[2].xy);
        } else {
            sUv = vec2[](vUv[2].xy, vUv[0].xy, vUv[1].xy);
        }

        // point on side perpendicular to sUv[0]
        vec2 oppositePoint = sUv[1] + pointToLine(sUv[1], sUv[2], sUv[0]) * (sUv[2] - sUv[1]);

        // calculate position of fragment's UV relative to
        // line between sUv[0] and oppositePoint
        float result = pointToLine(sUv[0], oppositePoint, fragUv);

        if (inverted)
        {
            result = 1 - result;
        }

        result = clamp(result, 0, 1);

        float distance = distance(sUv[0], oppositePoint);

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
}
