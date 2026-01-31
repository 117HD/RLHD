/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
#version 330

#include <uniforms/global.glsl>
#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>

in vec2 fScreenPos;

out vec4 FragColor;

void main() {
    // Calculate view direction per-pixel from screen position
    // For a skybox, we need the ray direction, not a world position
    // We unproject two points at different depths and get the direction between them

    // Unproject a point on the near plane and far plane
    vec4 nearClip = vec4(fScreenPos, -1.0, 1.0);
    vec4 farClip = vec4(fScreenPos, 1.0, 1.0);

    vec4 nearWorld = invProjectionMatrix * nearClip;
    vec4 farWorld = invProjectionMatrix * farClip;

    nearWorld /= nearWorld.w;
    farWorld /= farWorld.w;

    // The view direction is from near to far
    vec3 viewDir = normalize(farWorld.xyz - nearWorld.xyz);

    // Flip horizontal direction (X and Z) to match shadow direction, keep Y for correct altitude
    vec3 sunDir = normalize(vec3(skySunDir.x, -skySunDir.y, skySunDir.z));

    // Calculate how much the view is looking up vs down
    // viewDir.y is negative when looking up (due to coordinate system)
    float upAmount = -viewDir.y;

    // === DIRECTIONAL GRADIENT BASED ON SUN POSITION ===
    // Calculate the angular relationship between view direction and sun direction
    // This creates the effect where the sun side of the sky is brighter/warmer

    // Project both vectors onto the horizontal plane for horizontal gradient
    vec3 viewHorizontal = normalize(vec3(viewDir.x, 0.0, viewDir.z));
    vec3 sunHorizontal = normalize(vec3(sunDir.x, 0.0, sunDir.z));

    // Dot product gives us how much we're facing toward/away from sun horizontally
    // Range: -1 (facing away from sun) to +1 (facing toward sun)
    float sunFacing = dot(viewHorizontal, sunHorizontal);

    // Convert to 0-1 range: 0 = facing away from sun, 1 = facing toward sun
    float sunSideBlend = (sunFacing + 1.0) * 0.5;

    // Make the gradient more pronounced with smoothstep
    sunSideBlend = smoothstep(0.0, 1.0, sunSideBlend);

    // === VERTICAL GRADIENT (ZENITH TO HORIZON) ===
    float zenithBlend = smoothstep(-0.1, 0.7, upAmount);

    // === COMBINE COLORS ===
    // Create a "dark side" color that's darker and more blue/purple
    vec3 darkSideColor = skyZenithColor * 0.7; // Darker version of zenith

    // Create a "sun side" color that's the warm horizon color
    vec3 sunSideColor = skyHorizonColor;

    // Blend horizontally between dark side and sun side based on sun facing direction
    vec3 horizonColor = mix(darkSideColor, sunSideColor, sunSideBlend);

    // The zenith color is less affected by horizontal position but still slightly tinted
    vec3 zenithColor = mix(skyZenithColor * 0.9, skyZenithColor, sunSideBlend * 0.3);

    // Blend vertically between horizon and zenith
    vec3 skyColor = mix(horizonColor, zenithColor, zenithBlend);

    // === SUN GLOW EFFECT ===
    // Calculate angle between view direction and sun direction (full 3D)
    float sunDot = dot(viewDir, sunDir);

    // Create sun glow with multiple layers (smaller sun, less intense glow)
    if (sunDot > 0.0) {
        float coreGlow = pow(sunDot, 128.0) * 0.4;     // Tighter, smaller core
        float innerGlow = pow(sunDot, 32.0) * 0.25;    // Smaller inner glow
        float midGlow = pow(sunDot, 8.0) * 0.15;       // Reduced medium spread
        float outerGlow = pow(sunDot, 2.5) * 0.08;     // Subtler atmospheric glow

        float totalGlow = coreGlow + innerGlow + midGlow + outerGlow;
        skyColor += skySunColor * totalGlow;
    }

    // === HORIZON HAZE ===
    // Make the horizon slightly hazier/brighter, especially on the sun side
    float horizonHaze = 1.0 - abs(upAmount);
    horizonHaze = pow(horizonHaze, 2.5) * 0.15;
    vec3 hazeColor = mix(skyHorizonColor * 0.8, skyHorizonColor * 1.3, sunSideBlend);
    skyColor = mix(skyColor, hazeColor, horizonHaze);

    // === ATMOSPHERIC SCATTERING EFFECT ===
    // Add subtle warm tint on the sun side at the horizon during sunrise/sunset
    float atmosphericScatter = sunSideBlend * (1.0 - zenithBlend) * 0.2;
    skyColor = mix(skyColor, skySunColor * 0.5 + skyHorizonColor * 0.5, atmosphericScatter);

    // Apply gamma correction
    skyColor = pow(skyColor, vec3(gammaCorrection));

    // Apply color blindness compensation
    skyColor = colorBlindnessCompensation(skyColor);

    FragColor = vec4(skyColor, 1.0);
}
