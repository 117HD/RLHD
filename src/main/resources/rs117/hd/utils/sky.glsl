#pragma once

struct SkyGradient {
    vec3 sunDir;         // sun direction with the perceived-horizon offset applied
    float upAmount;      // how much the view is looking up (-viewDir.y)
    float sunSideBlend;  // 0 = facing away from sun, 1 = facing toward sun
    float zenithBlend;   // 0 = horizon, 1 = zenith
    float nightFade;     // 0 = deep night, 1 = sun at/above horizon
    vec3 color;          // base horizon/zenith gradient + sun glow (no haze/scatter/stars/moon yet)
};

// The player's perceived horizon is below the astronomical 0° due to camera angle.
// sin(5°) ≈ 0.087, meaning the sun will appear at horizon when actually at +5°
#define HORIZON_OFFSET 0.087

SkyGradient computeSkyGradient(vec3 viewDir) {
    SkyGradient g;

    g.sunDir = normalize(vec3(skySunDir.x, -skySunDir.y + HORIZON_OFFSET, skySunDir.z));
    g.upAmount = -viewDir.y;

    // Project onto the horizontal plane for the sun-facing gradient. Guard the
    // normalize and fade the directional bias toward neutral as the view nears
    // vertical, so the gradient doesn't pinch at the nadir/zenith.
    vec2 viewHoriz = vec2(viewDir.x, viewDir.z);
    float viewHorizLen = length(viewHoriz);
    vec3 viewHorizontal = viewHorizLen > 1e-4 ? vec3(viewHoriz.x, 0.0, viewHoriz.y) / viewHorizLen : vec3(0.0);
    vec3 sunHorizontal = normalize(vec3(g.sunDir.x, 0.0, g.sunDir.z));

    float sunFacing = dot(viewHorizontal, sunHorizontal) * smoothstep(0.0, 0.35, viewHorizLen);
    g.sunSideBlend = smoothstep(0.0, 1.0, (sunFacing + 1.0) * 0.5);

    g.zenithBlend = smoothstep(-0.1, 0.7, g.upAmount);

    float sunAltitude = clamp(skySunDir.y, 0.0, 1.0);
    float daytimeFactor = smoothstep(0.0, 0.64, sunAltitude);
    float dimFadeout = smoothstep(0.0, 0.34, sunAltitude);
    float darkSideDim = mix(0.7, 1.0, dimFadeout);
    vec3 darkSideColor = mix(skyZenithColor * darkSideDim, skyHorizonColor, daytimeFactor);
    vec3 sunSideColor = skyHorizonColor;

    g.nightFade = smoothstep(-0.26, 0.0, skySunDir.y);

    vec3 horizonColor = mix(darkSideColor, sunSideColor, g.sunSideBlend);
    horizonColor = mix(skyZenithColor, horizonColor, g.nightFade);

    g.color = mix(horizonColor, skyZenithColor, g.zenithBlend);

    // Sun glow. pow(x, 128/32/8) folded to repeated squaring; pow(x, 2.5) to x*x*sqrt(x).
    float sunDot = dot(viewDir, g.sunDir);
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
        g.color += skySunColor * (coreGlow + innerGlow + midGlow + outerGlow);
    }

    return g;
}

// Horizon haze + atmospheric scattering, applied on top of a sky gradient color.
vec3 applySkyHaze(vec3 skyColor, float upAmount, float sunSideBlend, float zenithBlend) {
    float horizonHaze = 1.0 - abs(upAmount);
    horizonHaze = horizonHaze * horizonHaze * sqrt(horizonHaze) * 0.15; // ^2.5
    vec3 hazeColor = mix(skyHorizonColor * 0.8, skyHorizonColor * 1.3, sunSideBlend);
    skyColor = mix(skyColor, hazeColor, horizonHaze);

    float atmosphericScatter = sunSideBlend * (1.0 - zenithBlend) * 0.2;
    skyColor = mix(skyColor, skySunColor * 0.5 + skyHorizonColor * 0.5, atmosphericScatter);

    return skyColor;
}