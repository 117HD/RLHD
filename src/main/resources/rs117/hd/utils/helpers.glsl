void populateLightVectors(inout Light light, vec3 dir, vec3 normals) {
    light.direction = dir;
    light.reflection = reflect(-light.direction, normals);
}

void populateLightDotProducts(inout Light light, Scene scene, vec3 normals) {
    light.ndl = max(dot(normals, light.direction), 0);
}

void populateSceneDotProducts(inout Scene scene, vec3 normals) {
    scene.vdn = max(dot(scene.viewDir, normals), 0);
    scene.ddn = max(dot(scene.downDir, normals), 0);
}

void populateSceneTerrainInformation(inout Scene scene) {
    // Water data
    bool isTerrain = (vTerrainData[0] & 1) != 0; // 1 = 0b1
    int waterDepth1 = vTerrainData[0] >> 8 & 0x7FF;
    int waterDepth2 = vTerrainData[1] >> 8 & 0x7FF;
    int waterDepth3 = vTerrainData[2] >> 8 & 0x7FF;
    float waterDepth =
        waterDepth1 * IN.texBlend.x +
        waterDepth2 * IN.texBlend.y +
        waterDepth3 * IN.texBlend.z;
    int waterTypeIndex = isTerrain ? vTerrainData[0] >> 3 & 0x1F : 0;
    WaterType waterType = getWaterType(waterTypeIndex);

    scene.isUnderwater = waterDepth != 0;
    scene.isWater = waterTypeIndex > 0 && !scene.isUnderwater;
    scene.waterType = waterType;
    scene.waterTypeIndex = waterTypeIndex;
    scene.waterDepth = waterDepth;
}

void adjustSceneUvs(inout Scene scene) {
    // Vanilla tree textures rely on UVs being clamped horizontally,
    // which HD doesn't do, so we instead opt to hide these fragments
    if ((vMaterialData[0] >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1) {
        scene.uvs[3].x = clamp(scene.uvs[3].x, 0, .984375);

        // Make fishing spots easier to see
        if (scene.materials[0].colorMap == MAT_WATER_DROPLETS.colorMap)
            scene.mipBias = -100;
    }

    // Not sure why we do this but i'll trust the process.
    scene.uvs[0] = scene.uvs[3];
    scene.uvs[1] = scene.uvs[3];
    scene.uvs[2] = scene.uvs[3];

    // Scroll UVs
    scene.uvs[0] += scene.materials[0].scrollDuration * elapsedTime;
    scene.uvs[1] += scene.materials[1].scrollDuration * elapsedTime;
    scene.uvs[2] += scene.materials[2].scrollDuration * elapsedTime;

    // Scale from the center
    scene.uvs[0] = (scene.uvs[0] - 0.5) * scene.materials[0].textureScale + 0.5;
    scene.uvs[1] = (scene.uvs[1] - 0.5) * scene.materials[1].textureScale + 0.5;
    scene.uvs[2] = (scene.uvs[2] - 0.5) * scene.materials[2].textureScale + 0.5;
}

void applyFlowmapToUvs(inout Scene scene) {
    vec2 flowMapUv = scene.uvs[0] - animationFrame(scene.materials[0].flowMapDuration);
    float flowMapStrength = scene.materials[0].flowMapStrength;
    if (scene.isUnderwater) {
        // Distort underwater textures
        flowMapUv = worldUvs(1.5) + animationFrame(10 * scene.waterType.duration) * vec2(1, -1);
        flowMapStrength = 0.075;
    }

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, scene.materials[0].flowMap)).xy;
    scene.uvs[0] += uvFlow * flowMapStrength;
    scene.uvs[1] += uvFlow * flowMapStrength;
    scene.uvs[2] += uvFlow * flowMapStrength;
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

void adjustFragPos(inout Scene scene, vec3 pos) {
    scene.fragPos = pos;
    #if PARALLAX_OCCLUSION_MAPPING
        mat3 invTBN = inverse(scene.TBN);
        vec3 tsViewDir = invTBN * scene.viewDir;
        vec3 tsLightDir = invTBN * -scene.sun.direction;

        vec3 fragDelta = vec3(0);
        float selfShadowing = 0;
        sampleDisplacementMap(scene.materials[0], tsViewDir, tsLightDir, scene.uvs[0], fragDelta, selfShadowing);
        sampleDisplacementMap(scene.materials[1], tsViewDir, tsLightDir, scene.uvs[1], fragDelta, selfShadowing);
        sampleDisplacementMap(scene.materials[2], tsViewDir, tsLightDir, scene.uvs[2], fragDelta, selfShadowing);

        // Average
        fragDelta /= 3;
        selfShadowing /= 3;

        scene.fragPos += scene.TBN * fragDelta;
    #endif
}

void postProcessImage(inout vec3 color) {
    color = clamp(color.rgb, 0, 1);

    // Skip unnecessary color conversion if possible
    if (saturation != 1 || contrast != 1) {
        vec3 hsv = srgbToHsv(color.rgb);

        // Apply saturation setting
        hsv.y *= saturation;

        // Apply contrast setting
        if (hsv.z > 0.5) {
            hsv.z = 0.5 + ((hsv.z - 0.5) * contrast);
        } else {
            hsv.z = 0.5 - ((0.5 - hsv.z) * contrast);
        }

        color.rgb = hsvToSrgb(hsv);
    }

    color.rgb = colorBlindnessCompensation(color.rgb);
}

void applyFog(inout vec4 color, Scene scene, vec3 pos, vec3 camPos, float fogStart, float fogEnd, float fogOpacity) {
    if (scene.isUnderwater)
        return;

    // ground fog
    float distance = distance(pos, camPos);
    float closeFadeDistance = 1500;
    float groundFog = 1.0 - clamp((pos.y - fogStart) / (fogEnd - fogStart), 0.0, 1.0);
    groundFog = mix(0.0, fogOpacity, groundFog);
    groundFog *= clamp(distance / closeFadeDistance, 0.0, 1.0);

    // multiply the visibility of each fog
    float combinedFog = 1 - (1 - IN.fogAmount) * (1 - groundFog);

    if (scene.isWater) {
        color.a = combinedFog + color.a * (1 - combinedFog);
    }

    color.rgb = mix(color.rgb, fogColor, combinedFog);
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

    if (underlayCount == 0 || overlayCount == 0) {
        // if a tile has all overlay or underlay vertices,
        // use the default blend

        underlayBlend = IN.texBlend;
        overlayBlend = IN.texBlend;
    } else {
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

    if (overlayCount > 0 && underlayCount > 0) {
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
            result = 1 - result;

        result = clamp(result, 0, 1);

        float distance = distance(sUv[0], oppositePoint);

        float cutoff = 0.5;

        result = (result - (1.0 - cutoff)) * (1.0 / cutoff);
        result = clamp(result, 0, 1);

        float maxDistance = 2.5;
        if (distance > maxDistance) {
            float multi = distance / maxDistance;
            result = 1.0 - ((1.0 - result) * multi);
            result = clamp(result, 0, 1);
        }

        overlayMix = result;
    }
}

void getSceneAlbedo(inout Scene scene) {
    int colorMap1 = scene.materials[0].colorMap;
    int colorMap2 = scene.materials[1].colorMap;
    int colorMap3 = scene.materials[2].colorMap;

    // get vertex colors
    vec4 flatColor = vec4(0.5, 0.5, 0.5, 1.0);
    vec4 baseColor1 = vColor[0];
    vec4 baseColor2 = vColor[1];
    vec4 baseColor3 = vColor[2];
    adjustVertexColors(baseColor1, baseColor2, baseColor3);

    // get diffuse textures
    vec4 texColor1 = colorMap1 == -1 ? vec4(1) : texture(textureArray, vec3(scene.uvs[0], colorMap1), scene.mipBias);
    vec4 texColor2 = colorMap2 == -1 ? vec4(1) : texture(textureArray, vec3(scene.uvs[1], colorMap2), scene.mipBias);
    vec4 texColor3 = colorMap3 == -1 ? vec4(1) : texture(textureArray, vec3(scene.uvs[2], colorMap3), scene.mipBias);
    texColor1.rgb *= scene.materials[0].brightness;
    texColor2.rgb *= scene.materials[1].brightness;
    texColor3.rgb *= scene.materials[2].brightness;

    // get fragment colors by combining vertex colors and texture samples
    vec4 texA = getMaterialShouldOverrideBaseColor(scene.materials[0]) ? texColor1 : vec4(texColor1.rgb * baseColor1.rgb, min(texColor1.a, baseColor1.a));
    vec4 texB = getMaterialShouldOverrideBaseColor(scene.materials[1]) ? texColor2 : vec4(texColor2.rgb * baseColor2.rgb, min(texColor2.a, baseColor2.a));
    vec4 texC = getMaterialShouldOverrideBaseColor(scene.materials[2]) ? texColor3 : vec4(texColor3.rgb * baseColor3.rgb, min(texColor3.a, baseColor3.a));

    // underlay / overlay color contribution
    vec4 underlayColor = vec4(0.0);
    vec4 overlayColor = vec4(0.0);
    float overlayMix = 0.0;
    getOverlayUnderlayColorBlend(vMaterialData, scene.uvs[3], IN.texBlend, texA, texB, texC, overlayColor, underlayColor, overlayMix);

    scene.albedo = mix(underlayColor, overlayColor, overlayMix);
}

void getSceneNormals(inout Scene scene, int flags) {
    if ((flags >> MATERIAL_FLAG_UPWARDS_NORMALS & 1) == 1) {
        scene.normals = vec3(0.0, -1.0, 0.0);
    } else {
        // Set up tangent-space transformation matrix
        vec3 N = normalize(IN.normal);
        mat3 TBN = mat3(T, B, N * min(length(T), length(B)));

        vec3 n1 = sampleNormalMap(scene.materials[0], scene.uvs[0], TBN);
        vec3 n2 = sampleNormalMap(scene.materials[1], scene.uvs[1], TBN);
        vec3 n3 = sampleNormalMap(scene.materials[2], scene.uvs[2], TBN);

        scene.TBN = TBN;
        scene.normals = normalize(n1 * scene.texBlend.x + n2 * scene.texBlend.y + n3 * scene.texBlend.z);
    }
}

void getSmoothnessAndReflectivity(inout Scene scene) {
    vec3 smoothness = vec3(scene.materials[0].specularGloss, scene.materials[1].specularGloss, scene.materials[2].specularGloss);
    vec3 reflectivity = vec3(scene.materials[0].specularStrength, scene.materials[1].specularStrength, scene.materials[2].specularStrength);
    reflectivity *= vec3(
        scene.materials[0].roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(scene.uvs[0], scene.materials[0].roughnessMap)).r),
        scene.materials[1].roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(scene.uvs[1], scene.materials[1].roughnessMap)).r),
        scene.materials[2].roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(scene.uvs[2], scene.materials[2].roughnessMap)).r)
    );

    // apply specular highlights to anything semi-transparent
    // this isn't always desirable but adds subtle light reflections to windows, etc.
    if (vColor[0].a + vColor[1].a + vColor[2].a < 2.99) {
        smoothness = vec3(30);
        reflectivity = vec3(
            clamp((1 - vColor[0].a) * 2, 0, 1),
            clamp((1 - vColor[1].a) * 2, 0, 1),
            clamp((1 - vColor[2].a) * 2, 0, 1)
        );
    }

    scene.smoothness = smoothness;
    scene.reflectivity = reflectivity;
}

float isMaterialUnlit(Scene scene) {
    float unlit = dot(scene.texBlend, vec3(
        getMaterialIsUnlit(scene.materials[0]),
        getMaterialIsUnlit(scene.materials[1]),
        getMaterialIsUnlit(scene.materials[2])
    ));

    return unlit;
}
