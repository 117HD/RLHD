#pragma once

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>
#include <uniforms/materials.glsl>
#include <uniforms/water_types.glsl>

#include <utils/constants.glsl>
#include <utils/misc.glsl>

#if SHADER_TYPE == FRAGMENT_SHADER

#ifndef DISPLAY_BASE_COLOR
#define DISPLAY_BASE_COLOR 0
#endif
#ifndef DISPLAY_UV
#define DISPLAY_UV 0
#endif
#ifndef DISPLAY_NORMAL
#define DISPLAY_NORMAL 0
#endif
#ifndef DISPLAY_TANGENT
#define DISPLAY_TANGENT 0
#endif
#ifndef DISPLAY_SHADOWS
#define DISPLAY_SHADOWS 0
#endif
#ifndef DISPLAY_LIGHTING
#define DISPLAY_LIGHTING 0
#endif

#include MATERIAL_CONSTANTS

uniform sampler2DArray textureArray;
uniform sampler2D shadowMap;
uniform usampler2DArray tiledLightingArray;

// general HD settings

flat in int fWorldViewId;
flat in ivec3 fAlphaBiasHsl;
flat in ivec3 fMaterialData;
flat in ivec3 fTerrainData;

#if FLAT_SHADING && ZONE_RENDERER
    flat in vec3 fFlatNormal;
#endif

in FragmentData {
    vec3 position;
    vec2 uv;
    vec3 normal;
    vec3 texBlend;
} IN;

vec2 worldUvs(float scale) {
    return -IN.position.xz / (128 * scale);
}

#include <utils/color_blindness.glsl>
#include <utils/caustics.glsl>
#include <utils/color_utils.glsl>
#include <utils/normals.glsl>
#include <utils/specular.glsl>
#include <utils/displacement.glsl>
#include <utils/shadows.glsl>
#include <utils/water.glsl>
#include <utils/color_filters.glsl>
#include <utils/fog.glsl>
#include <utils/wireframe.glsl>
#include <utils/lights.glsl>

#ifndef TILE_MATERIAL_COUNT
#define TILE_MATERIAL_COUNT 3
#endif

#if TILE_MATERIAL_COUNT >= 2
    #define TILE_PICK2(sampled, fallback) (sampled)
    #define TILE_CALL2(stmt) stmt
#else
    #define TILE_PICK2(sampled, fallback) (fallback)
    #define TILE_CALL2(stmt)
#endif

#if TILE_MATERIAL_COUNT >= 3
    #define TILE_PICK3(sampled, fallback) (sampled)
    #define TILE_CALL3(stmt) stmt
#else
    #define TILE_PICK3(sampled, fallback) (fallback)
    #define TILE_CALL3(stmt)
#endif

struct SurfaceSample {
    vec4 color;                  // blended albedo (rgb) + alpha, pre-lighting
    vec3 normals;
    float aoFactor;
    vec3 specularGloss;
    vec3 specularStrength;
    float unlit;
    float selfShadowing;
    vec3 fragPos;                // world position, POM-displaced if enabled
    bool disableShadowReceiving;
    // Debug-only: when set, shadeFragment() returns debugColor immediately
    // instead of proceeding to lighting.
    bool hasDebugOverride;
    vec4 debugColor;
};

SurfaceSample sampleSurface(vec3 viewDir, ivec4 tint, bool isUnderwater, WaterType waterType) {
    SurfaceSample surface;
    surface.hasDebugOverride = false;
    surface.selfShadowing = 0;
    surface.fragPos = IN.position;

    Material material1 = getMaterial(fMaterialData[0] >> MATERIAL_INDEX_SHIFT & MATERIAL_INDEX_MASK);
    #if TILE_MATERIAL_COUNT >= 2
        Material material2 = getMaterial(fMaterialData[1] >> MATERIAL_INDEX_SHIFT & MATERIAL_INDEX_MASK);
    #else
        #define material2 material1
    #endif
    #if TILE_MATERIAL_COUNT >= 3
        Material material3 = getMaterial(fMaterialData[2] >> MATERIAL_INDEX_SHIFT & MATERIAL_INDEX_MASK);
    #else
        #define material3 material1
    #endif

    surface.disableShadowReceiving = (fMaterialData[0] >> MATERIAL_FLAG_DISABLE_SHADOW_RECEIVING & 1) == 1;

    // set initial texture map ids
    int colorMap1 = material1.colorMap;
    int colorMap2 = material2.colorMap;
    int colorMap3 = material3.colorMap;

    // only use one flowMap map
    int flowMap = material1.flowMap;

    vec2 blendedUv = IN.uv;

    float mipBias = 0;
    // Vanilla tree textures rely on UVs being clamped horizontally, which HD doesn't do at the texture level.
    // Instead we manually clamp vanilla textures with transparency here. Including the transparency check
    // allows texture wrapping to work correctly for the mirror shield.
    if ((fMaterialData[0] >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1 && getMaterialHasTransparency(material1))
        blendedUv.x = clamp(blendedUv.x, 0, .984375);

    vec2 uv1 = blendedUv;
    vec2 uv2 = blendedUv;
    vec2 uv3 = blendedUv;

    // Scroll UVs
    uv1 += material1.scrollDuration * elapsedTime;
    uv2 += material2.scrollDuration * elapsedTime;
    uv3 += material3.scrollDuration * elapsedTime;

    // Scale from the center
    uv1 = (uv1 - .5) * material1.textureScale.xy + .5;
    uv2 = (uv2 - .5) * material2.textureScale.xy + .5;
    uv3 = (uv3 - .5) * material3.textureScale.xy + .5;

    // get flowMap map
    vec2 flowMapUv = uv1 - animationFrame(material1.flowMapDuration);
    float flowMapStrength = material1.flowMapStrength;
    if (isUnderwater)
    {
        // Distort underwater textures
        flowMapUv = worldUvs(1.5) + animationFrame(10 * waterType.duration) * vec2(1, -1);
        flowMapStrength = 0.075;
    }

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, flowMap)).xy;
    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;

    // Set up tangent-space transformation matrix

    vec3 N;
    #if FLAT_SHADING && ZONE_RENDERER
        N = normalize(fFlatNormal);
    #else
        N = normalize(IN.normal);
    #endif
    mat3 TBN = cotangent_frame(N, IN.position, IN.uv * -1.0);

    #if DISPLAY_UV
        if (DISPLAY_UV == 1) {
            surface.hasDebugOverride = true;
            surface.debugColor = vec4(fract(uv1 * IN.texBlend.x + uv2 * IN.texBlend.y + uv3 * IN.texBlend.z), 0.0, 1.0);
            return surface;
        }
    #endif

    #if DISPLAY_NORMAL
        if (DISPLAY_NORMAL == 1) {
            surface.hasDebugOverride = true;
            surface.debugColor = vec4(N * 0.5 + 0.5, 1.0);
            return surface;
        }
    #endif

    #if DISPLAY_TANGENT
        if (DISPLAY_TANGENT == 1) {
            surface.hasDebugOverride = true;
            surface.debugColor = vec4(TBN[0] * 0.5 + 0.5, 1.0);
            return surface;
        }
    #endif

    #if PARALLAX_OCCLUSION_MAPPING
        mat3 invTBN = inverse(TBN);
        vec3 tsViewDir = invTBN * viewDir;
        vec3 tsLightDir = invTBN * -lightDir;

        vec3 fragDelta = vec3(0);

        sampleDisplacementMap(material1, tsViewDir, tsLightDir, uv1, fragDelta, surface.selfShadowing);
        TILE_CALL2(sampleDisplacementMap(material2, tsViewDir, tsLightDir, uv2, fragDelta, surface.selfShadowing));
        TILE_CALL3(sampleDisplacementMap(material3, tsViewDir, tsLightDir, uv2, fragDelta, surface.selfShadowing));

        // Average over however many materials were actually sampled
        fragDelta /= float(TILE_MATERIAL_COUNT);
        surface.selfShadowing /= float(TILE_MATERIAL_COUNT);

        // Prevent displaced surfaces from casting flat shadows onto themselves
        fragDelta.z = max(0, fragDelta.z);

        surface.fragPos += TBN * fragDelta;
    #endif

    vec3 hsl1 = unpackRawHsl(fAlphaBiasHsl[0]);
    vec3 hsl2 = unpackRawHsl(fAlphaBiasHsl[1]);
    vec3 hsl3 = unpackRawHsl(fAlphaBiasHsl[2]);

    // Apply entity tint to HSL
    if (tint.w > 0) {
        hsl1 += ((tint.xyz - hsl1) * tint.w) / 128;
        hsl2 += ((tint.xyz - hsl2) * tint.w) / 128;
        hsl3 += ((tint.xyz - hsl3) * tint.w) / 128;
    }

    // get vertex colors
    vec4 baseColor1 = vec4(convertHsl(hsl1), 1 - float(fAlphaBiasHsl[0] >> 24 & 0xff) / 255.);
    vec4 baseColor2 = vec4(convertHsl(hsl2), 1 - float(fAlphaBiasHsl[1] >> 24 & 0xff) / 255.);
    vec4 baseColor3 = vec4(convertHsl(hsl3), 1 - float(fAlphaBiasHsl[2] >> 24 & 0xff) / 255.);

    // Convert to linear RGB
    baseColor1.rgb = srgbToLinear(hslToSrgb(baseColor1.xyz));
    baseColor2.rgb = srgbToLinear(hslToSrgb(baseColor2.xyz));
    baseColor3.rgb = srgbToLinear(hslToSrgb(baseColor3.xyz));

    #if DISPLAY_BASE_COLOR
    if (DISPLAY_BASE_COLOR == 1) { // Redundant, used for syntax highlighting in IntelliJ
        vec4 debugColor = baseColor1 * IN.texBlend.x + baseColor2 * IN.texBlend.y + baseColor3 * IN.texBlend.z;
        debugColor.rgb = linearToSrgb(debugColor.rgb);
        surface.hasDebugOverride = true;
        surface.debugColor = debugColor;
        return surface;
    }
    #endif

    vec4 texColor1 = colorMap1 == -1 ? vec4(1) : texture(textureArray, vec3(uv1, colorMap1), mipBias);
    vec4 texColor2 = TILE_PICK2(colorMap2 == -1 ? vec4(1) : texture(textureArray, vec3(uv2, colorMap2), mipBias), texColor1);
    vec4 texColor3 = TILE_PICK3(colorMap3 == -1 ? vec4(1) : texture(textureArray, vec3(uv3, colorMap3), mipBias), texColor1);

    texColor1.rgb *= material1.brightness;
    texColor2.rgb *= material2.brightness;
    texColor3.rgb *= material3.brightness;

    ivec3 isOverlay = ivec3(
        fMaterialData[0] >> MATERIAL_FLAG_IS_OVERLAY & 1,
        fMaterialData[1] >> MATERIAL_FLAG_IS_OVERLAY & 1,
        fMaterialData[2] >> MATERIAL_FLAG_IS_OVERLAY & 1
    );
    int overlayCount = isOverlay[0] + isOverlay[1] + isOverlay[2];
    ivec3 isUnderlay = ivec3(1) - isOverlay;
    int underlayCount = isUnderlay[0] + isUnderlay[1] + isUnderlay[2];

    // calculate blend amounts for overlay and underlay vertices
    vec3 underlayBlend = IN.texBlend * isUnderlay;
    vec3 overlayBlend = IN.texBlend * isOverlay;

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


    // get fragment colors by combining vertex colors and texture samples
    vec4 texA = getMaterialShouldOverrideBaseColor(material1) ? texColor1 : vec4(texColor1.rgb * baseColor1.rgb, min(texColor1.a, baseColor1.a));
    vec4 texB = getMaterialShouldOverrideBaseColor(material2) ? texColor2 : vec4(texColor2.rgb * baseColor2.rgb, min(texColor2.a, baseColor2.a));
    vec4 texC = getMaterialShouldOverrideBaseColor(material3) ? texColor3 : vec4(texColor3.rgb * baseColor3.rgb, min(texColor3.a, baseColor3.a));

    // combine fragment colors based on each blend, creating
    // one color for each overlay/underlay 'layer'
    vec4 underlayColor = texA * underlayBlend.x + texB * underlayBlend.y + texC * underlayBlend.z;
    vec4 overlayColor = texA * overlayBlend.x + texB * overlayBlend.y + texC * overlayBlend.z;

    float overlayMix = 0;

    if (overlayCount > 0 && underlayCount > 0)
    {
        ivec3 isPrimary = isUnderlay;
        bool invert = true;
        if (overlayCount == 1) {
            isPrimary = isOverlay;
            invert = false;
        }

        float result = dot(IN.texBlend, isPrimary);
        if (invert)
            result = 1 - result;

        result = clamp(result * 2 - 1, 0, 1);
        overlayMix = result;
    }

    surface.color = mix(underlayColor, overlayColor, overlayMix);

    // normals - material2/3 normal-map fetches skipped when not needed
    if ((fMaterialData[0] >> MATERIAL_FLAG_UPWARDS_NORMALS & 1) == 1) {
        surface.normals = vec3(0, -1, 0);
    } else {
        vec3 n1 = sampleNormalMap(material1, uv1, TBN);
        vec3 n2 = TILE_PICK2(sampleNormalMap(material2, uv2, TBN), n1);
        vec3 n3 = TILE_PICK3(sampleNormalMap(material3, uv3, TBN), n1);
        surface.normals = normalize(n1 * IN.texBlend.x + n2 * IN.texBlend.y + n3 * IN.texBlend.z);
    }

    // specular
    surface.specularGloss = vec3(material1.specularGloss, material2.specularGloss, material3.specularGloss);
    surface.specularStrength = vec3(material1.specularStrength, material2.specularStrength, material3.specularStrength);

    // roughness maps - material2/3 fetches skipped when not needed
    float roughness1 = material1.roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(uv1, material1.roughnessMap)).r);
    float roughness2 = TILE_PICK2(material2.roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(uv2, material2.roughnessMap)).r), roughness1);
    float roughness3 = TILE_PICK3(material3.roughnessMap == -1 ? 1 : linearToSrgb(texture(textureArray, vec3(uv3, material3.roughnessMap)).r), roughness1);
    surface.specularStrength *= vec3(roughness1, roughness2, roughness3);

    // apply specular highlights to anything semi-transparent
    // this isn't always desirable but adds subtle light reflections to windows, etc.
    if (baseColor1.a + baseColor2.a + baseColor3.a < 2.99)
    {
        surface.specularGloss = vec3(30);
        surface.specularStrength = vec3(
            clamp((1 - baseColor1.a) * 2, 0, 1),
            clamp((1 - baseColor2.a) * 2, 0, 1),
            clamp((1 - baseColor3.a) * 2, 0, 1)
        );
    }

    // ambient occlusion - material2/3 fetches skipped when not needed
    float ao1 = material1.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv1, material1.ambientOcclusionMap)).r;
    float ao2 = TILE_PICK2(material2.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv2, material2.ambientOcclusionMap)).r, ao1);
    float ao3 = TILE_PICK3(material2.ambientOcclusionMap == -1 ? 1 : texture(textureArray, vec3(uv3, material3.ambientOcclusionMap)).r, ao1);
    surface.aoFactor = IN.texBlend.x * ao1 + IN.texBlend.y * ao2 + IN.texBlend.z * ao3;

    surface.unlit = dot(IN.texBlend, vec3(
        getMaterialIsUnlit(material1),
        getMaterialIsUnlit(material2),
        getMaterialIsUnlit(material3)
    ));

    #if TILE_MATERIAL_COUNT < 2
        #undef material2
    #endif
    #if TILE_MATERIAL_COUNT < 3
        #undef material3
    #endif

    return surface;
}

vec4 shadeFragment() {
    // View & light directions are from the fragment to the camera/light
    vec3 viewDir = normalize(cameraPos - IN.position);

    // Water data
    bool isTerrain = (fTerrainData[0] & 1) != 0; // 1 = 0b1
    int waterDepth1 = fTerrainData[0] >> 11 & 0xFFF;
    int waterDepth2 = fTerrainData[1] >> 11 & 0xFFF;
    int waterDepth3 = fTerrainData[2] >> 11 & 0xFFF;
    float waterDepth =
        waterDepth1 * IN.texBlend.x +
        waterDepth2 * IN.texBlend.y +
        waterDepth3 * IN.texBlend.z;
    int waterTypeIndex = isTerrain ? fTerrainData[0] >> 3 & 0xFF : 0;
    WaterType waterType = getWaterType(waterTypeIndex);

    bool isUnderwater = waterDepth != 0;
    bool isWater = waterTypeIndex > 0 && !isUnderwater;

    vec4 outputColor = vec4(1);

    if (isWater) {
        outputColor = sampleWater(waterTypeIndex, viewDir);
    } else {
        // Material sampling/blending lives in sampleSurface() - it's the only
        // place that cares whether this is a 1-material (model) or
        // 3-material (terrain) tile. Everything below is the shared
        // lighting/fog/grading path and doesn't need to know which.
        ivec4 tint = getWorldViewTint(fWorldViewId);
        SurfaceSample surface = sampleSurface(viewDir, tint, isUnderwater, waterType);
        if (surface.hasDebugOverride)
            return surface.debugColor;

        vec3 downDir = vec3(0, -1, 0);
        float lightDotNormals = dot(surface.normals, lightDir);
        float downDotNormals = dot(downDir, surface.normals);
        float viewDotNormals = dot(viewDir, surface.normals);

        #if DISABLE_DIRECTIONAL_SHADING
            lightDotNormals = .7;
        #endif

        float shadow = 0;
        if (!surface.disableShadowReceiving)
            shadow = sampleShadowMap(surface.fragPos, vec2(0), lightDotNormals);
        shadow = max(shadow, surface.selfShadowing);
        float inverseShadow = 1 - shadow;

        #if DISPLAY_SHADOWS
            if (DISPLAY_SHADOWS == 1) return vec4(inverseShadow, inverseShadow, inverseShadow, 1.0);
        #endif

        float combinedSpecularStrength = dot(surface.specularStrength, IN.texBlend);

        // calculate lighting

        // ambient light
        vec3 ambientLightOut = ambientColor * ambientStrength;
        ambientLightOut *= surface.aoFactor;

        // directional light
        vec3 dirLightColor = lightColor * lightStrength;

        // underwater caustics based on directional light
        if (underwaterCaustics && underwaterEnvironment) {
            float scale = 12.8;
            vec2 causticsUv = worldUvs(scale);

            const ivec2 direction = ivec2(1, -1);
            const int driftSpeed = 231;
            vec2 drift = animationFrame(231) * ivec2(1, -2);
            vec2 flow1 = causticsUv + animationFrame(19) * direction + drift;
            vec2 flow2 = causticsUv * 1.25 + animationFrame(37) * -direction + drift;

            vec3 caustics = sampleCaustics(flow1, flow2) * 2;

            vec3 causticsColor = underwaterCausticsColor * underwaterCausticsStrength;
            dirLightColor += caustics * causticsColor * lightDotNormals * pow(lightStrength, 1.5);
        }

        // apply shadows
        dirLightColor *= inverseShadow;

        vec3 lightColor = dirLightColor;
        vec3 lightOut = max(lightDotNormals, 0.0) * lightColor;

        // directional light specular
        vec3 lightReflectDir = reflect(-lightDir, surface.normals);
        vec3 lightSpecularOut = lightColor * specular(IN.texBlend, viewDir, lightReflectDir, surface.specularGloss, surface.specularStrength);

        // point lights
        vec3 pointLightsOut = vec3(0);
        vec3 pointLightsSpecularOut = vec3(0);
        calculateLighting(IN.position, surface.normals, viewDir, IN.texBlend, surface.specularGloss, surface.specularStrength, pointLightsOut, pointLightsSpecularOut);

        // sky light
        vec3 skyLightColor = fogColor;
        float skyLightStrength = 0.5;
        float skyDotNormals = downDotNormals;
        vec3 skyLightOut = max(skyDotNormals, 0.0) * skyLightColor * skyLightStrength;


        // lightning
        vec3 lightningColor = vec3(.25, .25, .25);
        float lightningStrength = lightningBrightness;
        float lightningDotNormals = downDotNormals;
        vec3 lightningOut = max(lightningDotNormals, 0.0) * lightningColor * lightningStrength;


        // underglow
        vec3 underglowOut = underglowColor * max(surface.normals.y, 0) * underglowStrength;


        // fresnel reflection
        float baseOpacity = 0.4;
        float fresnel = 1.0 - clamp(viewDotNormals, 0.0, 1.0);
        float finalFresnel = clamp(mix(baseOpacity, 1.0, fresnel * 1.2), 0.0, 1.0);
        vec3 surfaceColor = vec3(0);
        vec3 surfaceColorOut = surfaceColor * max(combinedSpecularStrength, 0.2);


        // apply lighting
        vec3 compositeLight = ambientLightOut + lightOut + lightSpecularOut + skyLightOut + lightningOut +
        underglowOut + pointLightsOut + pointLightsSpecularOut + surfaceColorOut;

        #if DISPLAY_LIGHTING
            if (DISPLAY_LIGHTING == 1) return vec4(compositeLight, 1.0);
        #endif

        outputColor = surface.color;

        #if VANILLA_COLOR_BANDING
            outputColor.rgb = linearToSrgb(outputColor.rgb);
            outputColor.rgb = srgbToHsv(outputColor.rgb);
            outputColor.b = floor(outputColor.b * 127) / 127;
            outputColor.rgb = hsvToSrgb(outputColor.rgb);
            outputColor.rgb = srgbToLinear(outputColor.rgb);
        #endif

        if (tint.w > 0) {
            outputColor.rgb *= 1.0 + skyLightOut;
        } else {
            outputColor.rgb *= mix(compositeLight, vec3(1), surface.unlit);
        }
        outputColor.rgb = linearToSrgb(outputColor.rgb);

        if (isUnderwater) {
            sampleUnderwater(outputColor.rgb, waterType, waterDepth, lightDotNormals);
        }
    }

    #if LEGACY_RENDERER
        vec2 tiledist = abs(floor(IN.position.xz / 128) - floor(cameraPos.xz / 128));
        float maxDist = max(tiledist.x, tiledist.y);
        if (maxDist > drawDistance) {
            // Rapidly fade out any geometry that extends beyond the draw distance.
            // This is required if we always draw all underwater terrain.
            outputColor.a *= -256;
        }
    #endif

    outputColor.rgb = clamp(outputColor.rgb, 0, 1);

    // Skip unnecessary color conversion if possible
    if (saturation != 1 || contrast != 1) {
        vec3 hsv = srgbToHsv(outputColor.rgb);

        // Apply saturation setting
        hsv.y *= saturation;

        // Apply contrast setting
        if (hsv.z > 0.5) {
            hsv.z = 0.5 + ((hsv.z - 0.5) * contrast);
        } else {
            hsv.z = 0.5 - ((0.5 - hsv.z) * contrast);
        }

        outputColor.rgb = hsvToSrgb(hsv);
    }

    outputColor.rgb = colorBlindnessCompensation(outputColor.rgb);

    #if APPLY_COLOR_FILTER
        outputColor.rgb = applyColorFilter(outputColor.rgb);
    #endif

    #if WIREFRAME
        outputColor.rgb *= wireframeMask();
    #endif

    // apply fog
    if (!isUnderwater) {
        // ground fog
        float distance = distance(IN.position, cameraPos);
        float closeFadeDistance = 1500;
        float groundFog = 1.0 - clamp((IN.position.y - groundFogStart) / (groundFogEnd - groundFogStart), 0.0, 1.0);
        groundFog = mix(0.0, groundFogOpacity, groundFog);
        groundFog *= clamp(distance / closeFadeDistance, 0.0, 1.0);

        // multiply the visibility of each fog
        float fogAmount = calculateFogAmount(IN.position);
        float combinedFog = 1 - (1 - fogAmount) * (1 - groundFog);

        if (isWater) {
            outputColor.a = combinedFog + outputColor.a * (1 - combinedFog);
        }

        outputColor.rgb = mix(outputColor.rgb, fogColor, combinedFog);
    }

    outputColor.rgb = pow(outputColor.rgb, vec3(gammaCorrection));

    #if WINDOWS_HDR_CORRECTION
        outputColor.rgb = windowsHdrCorrection(outputColor.rgb);
    #endif

    return outputColor;
}

#else

struct SceneVertex {
    int vertex;
    int faceIdx;
    vec3 sceneOffset;
    vec3 worldPosition;
    bool hasWorldView;
    mat4x3 worldViewProjection;
    ivec3 faceAlphaBiasHsl;
    ivec3 faceMaterialData;
    ivec3 faceTerrainData;
};

SceneVertex resolveSceneVertex(vec3 localPosition, int textureFaceIdx, ivec2 sceneBase, int worldViewId) {
    SceneVertex v;

    v.vertex = gl_VertexID % 3;
    v.faceIdx = textureFaceIdx & 0x7FFFFFFF;
    if (textureFaceIdx < 0) // windingReversed
        v.vertex = 2 - v.vertex;

    v.sceneOffset = vec3(sceneBase.x, 0, sceneBase.y);
    v.worldPosition = v.sceneOffset + localPosition;

    v.hasWorldView = worldViewId != -1;
    if (v.hasWorldView) {
        v.worldViewProjection = mat4x3(getWorldViewProjection(worldViewId));
        v.worldPosition = v.worldViewProjection * vec4(v.worldPosition, 1.0);
    }

    v.faceAlphaBiasHsl = texelFetch(textureFaces, v.faceIdx).xyz;
    v.faceMaterialData = texelFetch(textureFaces, v.faceIdx + 1).xyz;
    v.faceTerrainData = texelFetch(textureFaces, v.faceIdx + 2).xyz;

    return v;
}

#endif