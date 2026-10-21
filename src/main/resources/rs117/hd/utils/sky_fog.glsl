#pragma once

#include <uniforms/sky.glsl>

// Keep the daytime sky visible through astronomical twilight.
float skyDaylightFactor() {
    return smoothstep(sin(radians(-18.0)), 0.0, uboSky.sunDir.y);
}

// Release opacity rather than optical depth, retaining subdued sunset colors
// before gradually revealing the night sky.
float skyFogDaylightFactor() {
    return smoothstep(sin(radians(-25.0)), 0.f, uboSky.sunDir.y);
}

float skyFogTransmittance(float upAmount) {
    // Broaden the horizon band for the game's finite view distance. This artistic
    // path approximation has a smooth, flat slope at the horizon and stays positive.
    float elevation = max(upAmount - uboSky.fogHorizonHeight, 0.0) / 0.16;
    float pathLength = 1.0 / (1.0 + elevation * elevation);
    float daytimeTransmittance = exp(-uboSky.fogDensity * pathLength);
    return uboSky.visibility * mix(1.0, daytimeTransmittance, skyFogDaylightFactor());
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
    // Attenuate the glow to feel approximately right
    return glow * (1.0 - transmittance) * sqrt(transmittance);
}
