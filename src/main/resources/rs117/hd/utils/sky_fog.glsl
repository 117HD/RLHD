#pragma once

#include <uniforms/sky.glsl>

float skyFogTransmittance(float upAmount) {
    // Broaden the horizon band for the game's finite view distance. This artistic
    // path approximation has a smooth, flat slope at the horizon and stays positive.
    float elevation = max(upAmount, 0.0) / 0.16;
    float pathLength = 1.0 / (1.0 + elevation * elevation);
    return uboSky.visibility * exp(-uboSky.fogDensity * pathLength);
}

// Forward lobe of Henyey-Greenstein scattering, normalized to one on-axis.
float skyFogForwardScatter(float lightDot) {
    const float anisotropy = 0.85;
    float denominator = 1.0 + anisotropy * anisotropy - 2.0 * anisotropy * clamp(lightDot, -1.0, 1.0);
    return pow(1.0 - anisotropy, 3.0) / max(denominator * sqrt(denominator), 1e-5);
}

vec3 applySkyFog(vec3 color, float transmittance) {
    return mix(uboSky.fogColor, color, transmittance);
}

vec3 skyFogGlow(vec3 viewDir, vec3 sunDir, vec3 moonDir, float transmittance) {
    vec3 glow =
        uboSky.sunColor * skyFogForwardScatter(dot(viewDir, sunDir)) * 0.08 +
        uboSky.moonDiskColor * skyFogForwardScatter(dot(viewDir, moonDir)) *
            uboSky.moonIllumination * uboSky.moonVisibility * 0.003;
    return glow * (1.0 - transmittance);
}
