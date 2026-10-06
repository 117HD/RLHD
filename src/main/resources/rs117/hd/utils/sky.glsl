#pragma once

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/celestial_projection.glsl>
#include <utils/starfield.glsl>
#include <utils/sky_fog.glsl>

struct SkyGradient {
    vec3 sunDir;         // sun direction with the perceived-horizon offset applied
    float upAmount;      // how much the view is looking up (-viewDir.y)
    float sunSideBlend;  // 0 = facing away from sun, 1 = facing toward sun
    float zenithBlend;   // 0 = horizon, 1 = zenith
    float nightFade;     // 0 = deep night, 1 = sun at/above horizon
    vec3 color;          // linear sRGB gradient + sun glow (before haze/stars/moon)
};

// The camera makes the perceived horizon about 5° below astronomical 0°.
#define HORIZON_OFFSET 0.087

SkyGradient computeSkyGradient(vec3 viewDir) {
    SkyGradient g;

    g.sunDir = normalize(vec3(uboSky.sunDir.x, -uboSky.sunDir.y + HORIZON_OFFSET, uboSky.sunDir.z));
    g.upAmount = -viewDir.y;

    // Fade the sun-facing bias near vertical views to avoid pinching.
    vec2 viewHoriz = vec2(viewDir.x, viewDir.z);
    float viewHorizLen = length(viewHoriz);
    vec3 viewHorizontal = viewHorizLen > 1e-4 ? vec3(viewHoriz.x, 0.0, viewHoriz.y) / viewHorizLen : vec3(0.0);
    vec3 sunHorizontal = normalize(vec3(g.sunDir.x, 0.0, g.sunDir.z));

    float sunFacing = dot(viewHorizontal, sunHorizontal) * smoothstep(0.0, 0.35, viewHorizLen);
    g.sunSideBlend = smoothstep(0.0, 1.0, (sunFacing + 1.0) * 0.5);

    g.zenithBlend = smoothstep(-0.1, 0.7, g.upAmount);

    float sunAltitude = clamp(uboSky.sunDir.y, 0.0, 1.0);
    float daytimeFactor = smoothstep(0.0, 0.64, sunAltitude);
    float dimFadeout = smoothstep(0.0, 0.34, sunAltitude);
    float darkSideDim = mix(0.7, 1.0, dimFadeout);
    vec3 darkSideColor = mix(uboSky.zenithColor * darkSideDim, uboSky.horizonColor, daytimeFactor);
    vec3 sunSideColor = uboSky.horizonColor;

    // Retain residual twilight until the sun reaches astronomical night at -18 degrees.
    g.nightFade = smoothstep(sin(radians(-18.0)), 0.0, uboSky.sunDir.y) * (1.0 - uboSky.customGradient);

    vec3 horizonColor = mix(darkSideColor, sunSideColor, g.sunSideBlend);
    horizonColor = mix(uboSky.zenithColor, horizonColor, g.nightFade);

    g.color = mix(horizonColor, uboSky.zenithColor, g.zenithBlend);

    // Custom skies have a symmetric horizon band independent of the sun's direction.
    vec3 customColor = mix(uboSky.horizonColor, uboSky.zenithColor,
        smoothstep(0.0, uboSky.horizonWidth, abs(g.upAmount)));
    g.color = mix(g.color, customColor, uboSky.customGradient);

    // Use multiply/sqrt equivalents of pow for the glow falloffs.
    // Below sunset, keep scattered sunlight at the perceived horizon. Its color and
    // disappearance are authored in sunGlow, independently of the sun disk's position.
    vec3 glowDir = normalize(vec3(uboSky.sunDir.x, -max(0.0, uboSky.sunDir.y) + HORIZON_OFFSET, uboSky.sunDir.z));
    float sunDot = dot(viewDir, glowDir);
    if (sunDot > 0.0) {
        float s2 = sunDot * sunDot;
        float s4 = s2 * s2;
        float s8 = s4 * s4;
        float s16 = s8 * s8;
        float s32 = s16 * s16;
        float s128 = s32 * s32; s128 = s128 * s128;
        float coreGlow = s128 * 0.4;
        float innerGlow = s32 * 0.25;
        float midGlow = s8 * 0.15;
        float outerGlow = s2 * sunDot * sqrt(sunDot) * 0.08;
        float diskDot = dot(celestialViewDirection(viewDir, g.sunDir), g.sunDir);
        float disk = smoothstep(0.99933, 0.99955, diskDot);
        g.color += uboSky.sunColor * (coreGlow + innerGlow + midGlow + outerGlow);
    }

    return g;
}

vec3 blendSkyBackground(vec3 gradient, vec3 background, float amount) {
    // Keep an authored gradient visible behind stars and nebulae, including below the horizon.
    return mix(gradient, background + gradient * uboSky.customGradient, amount);
}

float nightSkyBlend(SkyGradient sky) {
    float baseProgress = 1.0 - sky.nightFade;
    float sunProximity = sky.sunSideBlend * (1.0 - sky.zenithBlend);
    return pow(baseProgress, mix(0.4, 0.9, sunProximity));
}

float nightSkyHorizonFade(float upAmount, float horizonShift) {
    return smoothstep(-0.1 + horizonShift, 0.07 + horizonShift, upAmount);
}

// Broad visible sky color, excluding celestial disks, aurorae, and individual stars.
vec3 visibleSkyColor(SkyGradient sky, vec3 viewDir, float elapsedSeconds, float nebulaStrength) {
    float horizonShift = nightHorizonOffset(uboSky.starHorizonHeight);
    float amount = nightSkyBlend(sky) * nightSkyHorizonFade(sky.upAmount, horizonShift);
    if (amount <= 0.001)
        return sky.color;
    return blendSkyBackground(sky.color, nightSkyBackground(viewDir, elapsedSeconds, nebulaStrength), amount);
}

// Linear sky radiance for scene fog and broad water reflections, without disks or stars.
vec3 foggedSkyColor(vec3 viewDir) {
    SkyGradient sky = computeSkyGradient(viewDir);
    vec3 color = visibleSkyColor(sky, viewDir, uboGlobal.elapsedTime, 1.0);
    vec3 moonDir = normalize(vec3(uboSky.moonDir.x, -uboSky.moonDir.y + HORIZON_OFFSET, uboSky.moonDir.z));
    float transmittance = skyFogTransmittance(sky.upAmount);
    return applySkyFog(color, transmittance) + skyFogGlow(viewDir, sky.sunDir, moonDir, transmittance);
}
