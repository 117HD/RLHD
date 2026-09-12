#pragma once

#include <uniforms/sky.glsl>

float skyFogTransmittance(float upAmount) {
    // Broaden the horizon band for the game's finite view distance. This artistic
    // path approximation has a smooth, flat slope at the horizon and stays positive.
    float elevation = max(upAmount, 0.0) / 0.16;
    float pathLength = 1.0 / (1.0 + elevation * elevation);
    return exp(-skyFogDensity * pathLength);
}

vec3 applySkyFog(vec3 color, float upAmount) {
    return mix(skyFogColor, color, skyFogTransmittance(upAmount));
}
