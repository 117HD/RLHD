#pragma once

void populateLightVectors(inout Light light, vec3 dir, vec3 normals) {
    light.direction = dir;
    light.reflection = reflect(-dir, normals);
}

void populateLightDotProducts(inout Light light, Context ctx) {
    light.ndl = max(dot(ctx.normals, light.direction), 0);
}

void populateContextDotProducts(inout Context ctx) {
    ctx.vdn = max(dot(ctx.viewDir, ctx.normals), 0);
    ctx.udn = max(-ctx.normals.y, 0);
}

void populateSceneTerrainInformation(inout Context ctx) {
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

    ctx.isUnderwater = waterDepth != 0;
    ctx.isWater = waterTypeIndex > 0 && !ctx.isUnderwater;
    ctx.waterType = waterType;
    ctx.waterTypeIndex = waterTypeIndex;
    ctx.waterDepth = waterDepth;
}

void populateUvs(inout Context ctx) {
    // Vanilla tree textures rely on UVs being clamped horizontally,
    // which HD doesn't do, so we instead opt to hide these fragments
    if ((ctx.materialData >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1) {
        ctx.uvs[3].x = clamp(ctx.uvs[3].x, 0, .984375);

        // Make fishing spots easier to see
        if (ctx.materials[0].colorMap == MAT_WATER_DROPLETS.colorMap)
            ctx.mipBias = -100;
    }

    // Not sure why we do this but i'll trust the process.
    ctx.uvs[0] = ctx.uvs[3];
    ctx.uvs[1] = ctx.uvs[3];
    ctx.uvs[2] = ctx.uvs[3];

    // Scroll UVs
    ctx.uvs[0] += ctx.materials[0].scrollDuration * elapsedTime;
    ctx.uvs[1] += ctx.materials[1].scrollDuration * elapsedTime;
    ctx.uvs[2] += ctx.materials[2].scrollDuration * elapsedTime;

    // Scale from the center
    ctx.uvs[0] = (ctx.uvs[0] - 0.5) * ctx.materials[0].textureScale + 0.5;
    ctx.uvs[1] = (ctx.uvs[1] - 0.5) * ctx.materials[1].textureScale + 0.5;
    ctx.uvs[2] = (ctx.uvs[2] - 0.5) * ctx.materials[2].textureScale + 0.5;
}

void applyUvFlow(inout Context ctx) {
    vec2 flowMapUv = ctx.uvs[0] - animationFrame(ctx.materials[0].flowMapDuration);
    float flowMapStrength = ctx.materials[0].flowMapStrength;
    if (ctx.isUnderwater) {
        // Distort underwater textures
        flowMapUv = worldUvs(1.5) + animationFrame(10 * ctx.waterType.duration) * vec2(1, -1);
        flowMapStrength = 0.075;
    }

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, ctx.materials[0].flowMap)).xy;
    ctx.uvs[0] += uvFlow * flowMapStrength;
    ctx.uvs[1] += uvFlow * flowMapStrength;
    ctx.uvs[2] += uvFlow * flowMapStrength;
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

void applyFog(inout vec4 color, Context ctx, vec3 pos, vec3 camPos, float fogStart, float fogEnd, float fogOpacity) {
    if (ctx.isUnderwater)
        return;

    // ground fog
    float distance = distance(pos, camPos);
    float closeFadeDistance = 1500;
    float groundFog = 1.0 - clamp((pos.y - fogStart) / (fogEnd - fogStart), 0.0, 1.0);
    groundFog = mix(0.0, fogOpacity, groundFog);
    groundFog *= clamp(distance / closeFadeDistance, 0.0, 1.0);

    // multiply the visibility of each fog
    float combinedFog = 1 - (1 - IN.fogAmount) * (1 - groundFog);

    if (ctx.isWater) {
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
            sUv = vec2[](vUv[0], vUv[1], vUv[2]);
        } else if (isPrimary[1] == 1) {
            sUv = vec2[](vUv[1], vUv[0], vUv[2]);
        } else {
            sUv = vec2[](vUv[2], vUv[0], vUv[1]);
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

vec4 sampleColorMap(const Material mat, const vec2 uv, const float mipBias) {
    vec4 color = mat.colorMap == -1 ? vec4(1) : texture(textureArray, vec3(uv, mat.colorMap), mipBias);
    color.rgb *= mat.brightness;
    return color;
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

void populateAlbedo(inout Context ctx) {
    // get vertex colors
    vec4 baseColor1 = vColor[0];
    vec4 baseColor2 = vColor[1];
    vec4 baseColor3 = vColor[2];
    adjustVertexColors(baseColor1, baseColor2, baseColor3);

    // get diffuse textures
    vec4 texColor1 = sampleColorMap(ctx.materials[0], ctx.uvs[0], ctx.mipBias);
    vec4 texColor2 = sampleColorMap(ctx.materials[1], ctx.uvs[1], ctx.mipBias);
    vec4 texColor3 = sampleColorMap(ctx.materials[2], ctx.uvs[2], ctx.mipBias);

    // get fragment colors by combining vertex colors and texture samples
    vec4 texA = getMaterialShouldOverrideBaseColor(ctx.materials[0]) ? texColor1 : vec4(texColor1.rgb * baseColor1.rgb, min(texColor1.a, baseColor1.a));
    vec4 texB = getMaterialShouldOverrideBaseColor(ctx.materials[1]) ? texColor2 : vec4(texColor2.rgb * baseColor2.rgb, min(texColor2.a, baseColor2.a));
    vec4 texC = getMaterialShouldOverrideBaseColor(ctx.materials[2]) ? texColor3 : vec4(texColor3.rgb * baseColor3.rgb, min(texColor3.a, baseColor3.a));

    // underlay / overlay color contribution
    vec4 underlayColor = vec4(0.0);
    vec4 overlayColor = vec4(0.0);
    float overlayMix = 0.0;
    getOverlayUnderlayColorBlend(vMaterialData, ctx.uvs[3], IN.texBlend, texA, texB, texC, overlayColor, underlayColor, overlayMix);

    ctx.albedo = mix(underlayColor, overlayColor, overlayMix);
}

void populateTangentSpaceMatrix(inout Context ctx) {
    vec3 N = normalize(IN.normal);
    ctx.TBN = mat3(T, B, N * min(length(T), length(B)));
}

void populateNormals(inout Context ctx) {
    if ((ctx.materialData >> MATERIAL_FLAG_UPWARDS_NORMALS & 1) == 1) {
        ctx.normals = vec3(0.0, -1.0, 0.0);
    } else {
        vec3 n1 = sampleNormalMap(ctx.materials[0], ctx.uvs[0], ctx.TBN);
        vec3 n2 = sampleNormalMap(ctx.materials[1], ctx.uvs[1], ctx.TBN);
        vec3 n3 = sampleNormalMap(ctx.materials[2], ctx.uvs[2], ctx.TBN);
        ctx.normals = normalize(n1 * ctx.texBlend.x + n2 * ctx.texBlend.y + n3 * ctx.texBlend.z);
    }
}

float sampleRoughness(const Material mat, const vec2 uv) {
    if (mat.roughnessMap == -1)
        return 1;
    return linearToSrgb(texture(textureArray, vec3(uv, mat.roughnessMap)).r);
}

void populateSmoothnessAndReflectivity(inout Context ctx) {
    vec3 smoothness = vec3(ctx.materials[0].specularGloss, ctx.materials[1].specularGloss, ctx.materials[2].specularGloss);
    vec3 reflectivity = vec3(ctx.materials[0].specularStrength, ctx.materials[1].specularStrength, ctx.materials[2].specularStrength);
    reflectivity *= vec3(
        sampleRoughness(ctx.materials[0], ctx.uvs[0]),
        sampleRoughness(ctx.materials[1], ctx.uvs[1]),
        sampleRoughness(ctx.materials[2], ctx.uvs[2])
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

    ctx.smoothness = smoothness;
    ctx.reflectivity = reflectivity;
}

float isMaterialUnlit(Context ctx) {
    float unlit = dot(ctx.texBlend, vec3(
        getMaterialIsUnlit(ctx.materials[0]),
        getMaterialIsUnlit(ctx.materials[1]),
        getMaterialIsUnlit(ctx.materials[2])
    ));

    return unlit;
}
