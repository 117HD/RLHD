#pragma once

#include <utils/color_utils.glsl>

// Raymarched aurora curtains through thin elevated layers.

float au_hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float au_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(au_hash21(i), au_hash21(i + vec2(1.0, 0.0)), f.x),
        mix(au_hash21(i + vec2(0.0, 1.0)), au_hash21(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

// Return the curtain ridge at a horizontal position.
float auroraCurtain(float x, float time) {
    float wave = sin(x * 1.8 - time * 0.4) * 0.5;
    wave += sin(x * 3.1 + time * 0.25) * 0.25;
    wave += sin(x * 7.0 - time * 0.6) * 0.08;
    wave += au_noise(vec2(x * 0.8 + time * 0.05, time * 0.03)) * 0.4;
    return wave;
}

// Sample one aurora layer at planeY.
vec3 auroraLayer(vec3 ro, vec3 rd, float planeY, float time, float layerSeed) {
    if (rd.y >= 0.0) return vec3(0.0); // ray going down, won't hit sky plane
    float t = (planeY - ro.y) / rd.y;
    if (t < 0.0) return vec3(0.0);

    vec3 hit = ro + rd * t;

    float curtainX = hit.x * 0.0005 + layerSeed * 3.7;
    float curtainZ = hit.z * 0.0005;

    float wave = auroraCurtain(curtainX, time + layerSeed * 5.0);

    float distFromCurtain = abs(curtainZ - wave);
    float curtainMask = exp(-distFromCurtain * distFromCurtain * 55.0);

    // Keep the ray texture continuous so the curtain remains a flowing wave.
    float rayNoise = au_noise(vec2(curtainX * 12.0 + layerSeed * 10.0, time * 0.1));
    float rays = smoothstep(0.2, 0.8, rayNoise);
    rays = mix(0.55, 1.0, rays);

    float brightness = au_noise(vec2(curtainX * 2.0 - time * 0.02, layerSeed));
    brightness = 0.4 + brightness * 0.6;

    float distFade = exp(-t * t * 0.0000001);

    float intensity = curtainMask * rays * brightness * distFade;

    // Slowly vary the emissive green base between teal and yellow-green.
    float hueNoise = au_noise(vec2(curtainX * 1.3 + layerSeed * 4.0, time * 0.04 + layerSeed));
    vec3 tealGreen = srgbToLinear(vec3(0.0, 1.5, 0.9));   // cool cyan-green
    vec3 warmGreen = srgbToLinear(vec3(0.35, 1.7, 0.25)); // warm yellow-green
    vec3 green = mix(tealGreen, warmGreen, smoothstep(0.25, 0.75, hueNoise));

    float heightBlend = (planeY - 600.0) / 600.0; // 0 at bottom, 1 at top of aurora range
    vec3 purple = srgbToLinear(vec3(0.9, 0.2, 1.2));
    vec3 col = mix(green, purple, smoothstep(0.0, 1.0, heightBlend));

    return col * intensity;
}

// Return additive aurora emission in linear sRGB.
vec3 proceduralAurora(vec3 viewDir, float time) {
    float upAmount = -viewDir.y;

    if (upAmount < 0.01) return vec3(0.0);

    // North-facing gate: aurora in the northern sky (+Z)
    float northFacing = viewDir.z;
    if (northFacing < -0.2) return vec3(0.0);
    float northBlend = smoothstep(-0.2, 0.3, northFacing);

    float horizonFade = smoothstep(0.01, 0.06, upAmount);

    vec3 ro = vec3(0.0, 0.0, 0.0);
    vec3 rd = normalize(viewDir);

    // A few planes add depth without turning the curtains into a dense mass.
    vec3 aurora = vec3(0.0);
    aurora += auroraLayer(ro, rd, -600.0, time, 1.0) * 0.75;
    aurora += auroraLayer(ro, rd, -800.0, time, 2.0) * 1.0;
    aurora += auroraLayer(ro, rd, -1050.0, time, 3.0) * 0.5;

    aurora *= northBlend * horizonFade;

    return aurora * 0.26;
}
