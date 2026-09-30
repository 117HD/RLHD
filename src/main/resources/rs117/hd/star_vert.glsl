#version 330

// Point-sprite stars: per-star work scales with star count, not screen pixels.

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/hash.glsl>
#include <utils/color_utils.glsl>
#include <utils/starfield.glsl>
#include <utils/sky_fog.glsl>
#include <utils/celestial_projection.glsl>

layout(location = 0) in vec3 aStarDir;     // field-space unit direction
layout(location = 1) in float aStarSize;   // relative size
layout(location = 2) in float aStarBright; // base brightness
layout(location = 3) in vec3 aStarColor;   // linear sRGB tint
layout(location = 4) in float aStarRotationSpeed;

out vec3 vColor;
out float vBrightness;

const float SKY_HORIZON_OFFSET = 0.087;

vec3 starGaussian(vec2 seed) {
    vec4 u = hash42(seed);
    vec2 radius = sqrt(-2.0 * log(max(u.xy, vec2(1e-6))));
    vec2 angle = TAU * u.zw;
    return vec3(radius.x * cos(angle.x), radius.x * sin(angle.x), radius.y * cos(angle.y));
}

vec3 starNoise(float time, float seed) {
    float cell = floor(time);
    float t = fract(time);
    float w = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    // Independent unit Gaussians, smoothly joined without losing variance between knots.
    return mix(starGaussian(vec2(cell, seed)), starGaussian(vec2(cell + 1.0, seed)), w)
        * inversesqrt((1.0 - w) * (1.0 - w) + w * w);
}

void main() {
    if (orthographicProjection) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        vColor = vec3(0.0);
        vBrightness = 0.0;
        return;
    }

    vec3 dir = inverseRotateStarfield(aStarDir, elapsedTime, aStarRotationSpeed);

    // Softly occlude additively blended stars behind the opaque moon disk.
    float moonOcclusion = 1.0;
    if (uboSky.moonVisibility > 0.0) {
        vec3 moonDir = normalize(vec3(uboSky.moonDir.x, -uboSky.moonDir.y + SKY_HORIZON_OFFSET, uboSky.moonDir.z));
        float moonDot = dot(celestialViewDirection(dir, moonDir), moonDir);
        // Match the moon disk's per-environment angular scale.
        float innerAngle = acos(0.99951) * uboSky.moonSizeMult;
        float outerAngle = acos(0.9991) * uboSky.moonSizeMult;
        moonOcclusion = smoothstep(cos(innerAngle), cos(outerAngle), moonDot);
    }

    // Project a far point from the camera; depth testing is disabled for this pass.
    vec4 clip = projectionMatrix * vec4(cameraPos + dir * 1.0e6, 1.0);
    if (clip.w <= 0.0) {
        // Push stars behind the camera off-screen.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        return;
    }
    gl_Position = clip;

    // Match sky_frag's night-sky visibility.
    float upAmount = -dir.y;

    vec3 sunDir = normalize(vec3(uboSky.sunDir.x, -uboSky.sunDir.y + SKY_HORIZON_OFFSET, uboSky.sunDir.z));
    vec2 viewHoriz = vec2(dir.x, dir.z);
    float viewHorizLen = length(viewHoriz);
    vec3 viewHorizontal = viewHorizLen > 1e-4 ? vec3(viewHoriz.x, 0.0, viewHoriz.y) / viewHorizLen : vec3(0.0);
    vec3 sunHorizontal = normalize(vec3(sunDir.x, 0.0, sunDir.z));
    float sunFacing = dot(viewHorizontal, sunHorizontal) * smoothstep(0.0, 0.35, viewHorizLen);
    float sunSideBlend = smoothstep(0.0, 1.0, (sunFacing + 1.0) * 0.5);

    float zenithBlend = smoothstep(-0.1, 0.7, upAmount);
    float nightFade = smoothstep(-0.26, 0.0, uboSky.sunDir.y) * (1.0 - uboSky.customGradient);

    float baseProgress = 1.0 - nightFade;
    float sunProximity = sunSideBlend * (1.0 - zenithBlend);
    float nightSkyBlend = pow(baseProgress, mix(0.4, 0.9, sunProximity)) * uboSky.starVisibility;

    // Fade stars just above the nebula horizon band.
    float horizonShift = nightHorizonOffset(uboSky.starHorizonHeight);
    float horizonStarFade = smoothstep(horizonShift, 0.12 + horizonShift, upAmount);

    float visibility = nightSkyBlend * horizonStarFade * moonOcclusion;

    // R = log-intensity deviation
    // G = fluctuation rate
    // B = chromatic deviation
    // A = coherence averaging
    vec4 tuning = vec4(0.35, 0.43, 0.35, 0.6);
    vec2 starHash = hash23(aStarDir);
    float rate = mix(3.0, 16.0, tuning.g) * mix(0.8, 1.2, starHash.y);
    float seed = starHash.x * 4096.0;
    float time = elapsedTime * rate + starHash.y;
    // Two independent turbulence scales give irregular flickers within slower swells.
    // Squared weights sum to one, preserving the unit Gaussian distribution.
    vec3 noise = 0.8 * starNoise(time, seed) + 0.6 * starNoise(time * 0.19, seed + 8192.0);
    // Scintillation increases with air mass, but a bounded 1–1.75x response is
    // easier to tune than the singular geometric air-mass curve at the horizon.
    float lowAltitude = 1.0 - smoothstep(0.0, 0.6, max(upAmount, 0.0));
    float altitudeScale = mix(1.0, 1.75, lowAltitude);
    float sigma = 0.9 * tuning.r * altitudeScale;
    // A slowly drifting continuous field makes strong dispersion sparse without
    // assigning abruptly different behavior to neighboring or moving stars.
    vec3 chromaFieldPosition = dir * 12.0 + elapsedTime * vec3(0.017, -0.013, 0.011);
    float chromaHotspot = smoothstep(0.78, 0.95, sf_noise(chromaFieldPosition + vec3(73.0)));
    float chroma = 0.65 * tuning.b * 2 * altitudeScale * mix(0.2, 1.35, chromaHotspot);
    // Dispersion mostly shifts the visible spectrum between its red and blue extremes.
    // Retain a much weaker green-magenta component for occasional intermediate flashes.
    const vec3 redBlueDispersion = vec3(1.0, 0.0, -1.0);
    const vec3 greenDispersion = vec3(-0.0525, 0.105, -0.0525);
    vec3 logGain = vec3(sigma * noise.x)
        + chroma * (noise.y * redBlueDispersion + noise.z * greenDispersion);
    vec3 chromaVariance = redBlueDispersion * redBlueDispersion + greenDispersion * greenDispersion;
    vec3 variance = vec3(sigma * sigma) + chroma * chroma * chromaVariance;
    // Positive lognormal gains: E[exp(X - variance/2)] = 1 for each RGB channel.
    // Dispersion redistributes color over time without adding a permanent tint or energy.
    vec3 scintillation = exp(logGain - 0.5 * variance);
    // A larger/brighter apparent disk averages multiple refracted contributions:
    // only part of its light scintillates coherently. Faint point-like stars can
    // follow the full gain and briefly fall below the display/visual threshold.
    float prominence = clamp(aStarSize, 0.0, 1.0) * sqrt(clamp(aStarBright / 0.4, 0.0, 1.0));
    float coherentFraction = 1.0 - 0.75 * tuning.a * prominence;
    vColor = aStarColor * mix(vec3(1.0), scintillation, coherentFraction);
    vBrightness = min(aStarBright, .4) * visibility;

    // Size in screen pixels, then enforce the same anti-flicker floor in FBO pixels.
    float viewportHeight = max(float(viewportSize.y), 1.0);
    float screenSize = clamp(aStarSize * viewportHeight * 0.003 * (0.9 + 0.15 * vBrightness), 2.0, 3.5);
    float renderScale = float(sceneResolution.y) / viewportHeight;
    float sizePixels = max(screenSize * renderScale, 2.0);
    float actualScreenSize = sizePixels / max(renderScale, 1e-6);
    vBrightness *= min(1.0, (screenSize / actualScreenSize) * (screenSize / actualScreenSize));
    vBrightness *= skyFogTransmittance(upAmount);
    gl_PointSize = visibility > 0.001 ? sizePixels : 0.0;
}
