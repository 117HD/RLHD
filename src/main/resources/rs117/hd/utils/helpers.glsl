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

void applyFlowmapToUvs(inout Scene scene, bool isUnderwater, WaterType waterType)
{
    vec2 flowMapUv = scene.uvs[0] - animationFrame(scene.materials[0].flowMapDuration);
    float flowMapStrength = scene.materials[0].flowMapStrength;
    if (isUnderwater)
    {
        // Distort underwater textures
        flowMapUv = worldUvs(1.5) + animationFrame(10 * waterType.duration) * vec2(1, -1);
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

float getAmbientOcclusion(Scene scene) {
    return
        scene.texBlend.x * (scene.materials[0].ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(scene.uvs[0], scene.materials[0].ambientOcclusionMap)).r) +
        scene.texBlend.y * (scene.materials[1].ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(scene.uvs[1], scene.materials[1].ambientOcclusionMap)).r) +
        scene.texBlend.z * (scene.materials[2].ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(scene.uvs[2], scene.materials[2].ambientOcclusionMap)).r);
}