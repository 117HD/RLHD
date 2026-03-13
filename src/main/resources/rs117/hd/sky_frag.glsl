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

uniform sampler2D nightSkyTexture;

// Moon surface noise functions
float moonHash(in vec2 st) {
    return fract(sin(dot(st.xy, vec2(12.9898, 78.233))) * 43758.5453123);
}

float moonNoise(in vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    float a = moonHash(i);
    float b = moonHash(i + vec2(1.0, 0.0));
    float c = moonHash(i + vec2(0.0, 1.0));
    float d = moonHash(i + vec2(1.0, 1.0));
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(a, b, u.x) +
        (c - a) * u.y * (1.0 - u.x) +
        (d - b) * u.x * u.y;
}

float moonFbm(in vec2 st) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; i++) {
        value += amplitude * moonNoise(st);
        st *= 2.0;
        amplitude *= 0.5;
    }
    return value;
}

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

    // Transform sun direction to view space
    // The player's perceived horizon is below the astronomical 0° due to camera angle
    // Add an offset to lower the sun so it visually sets at the perceived horizon
    // sin(5°) ≈ 0.087, meaning the sun will appear at horizon when actually at +5°
    float horizonOffset = 0.087;
    vec3 sunDir = normalize(vec3(skySunDir.x, -skySunDir.y + horizonOffset, skySunDir.z));

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
    // Use sun altitude to determine how much directional gradient to apply
    // skySunDir.y = sin(altitude), positive when sun is up, negative when below horizon
    float sunAltitude = clamp(skySunDir.y, 0.0, 1.0); // 0 at horizon, 1 when high
    // sin(40°) ≈ 0.64, so we reach 100% at ~40 degrees sun altitude
    float daytimeFactor = smoothstep(0.0, 0.64, sunAltitude); // Ramps up as sun rises

    // During sunrise/sunset (low daytimeFactor): use darker zenith for dark side
    // During daytime (high daytimeFactor): both sides use horizon color for uniform sky
    // sin(20°) ≈ 0.34, so dimming fades out by ~20° sun altitude (faster than daytimeFactor)
    float dimFadeout = smoothstep(0.0, 0.34, sunAltitude);
    float darkSideDim = mix(0.7, 1.0, dimFadeout); // Reaches 1.0 at ~20° sun altitude
    vec3 darkSideColor = mix(skyZenithColor * darkSideDim, skyHorizonColor, daytimeFactor);

    // Create a "sun side" color that's the warm horizon color
    vec3 sunSideColor = skyHorizonColor;

    // Fade out directional gradient at night so sky becomes uniform
    // skySunDir.y = sin(altitude): 0 at horizon, negative below
    // Ramp from full effect at 0° to no effect at -15° (matching night sky blend)
    float nightFade = smoothstep(-0.26, 0.0, skySunDir.y); // sin(-15°) ≈ -0.26

    // Blend horizontally between dark side and sun side based on sun facing direction
    // At night, blend toward uniform skyZenithColor instead
    vec3 horizonColor = mix(darkSideColor, sunSideColor, sunSideBlend);
    horizonColor = mix(skyZenithColor, horizonColor, nightFade);

    // The zenith color - during daytime use full brightness, during sunset allow some dimming
    vec3 zenithColor = skyZenithColor;

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

    // Save sky color before stars are blended in, for opaque moon dark side
    vec3 skyColorPreStars = skyColor;

    // === NIGHT SKY TEXTURE ===
    // Fade in the starfield texture as sun drops below horizon
    // nightFade is already 0 at -15° and 1 at 0°, so we use its inverse
    // starVisibility (from environment override): 0 = no stars (opaque skybox), 1 = full stars
    float nightSkyBlend = (1.0 - nightFade) * starVisibility;
    if (nightSkyBlend > 0.001) {
        // Rotate the star map around two axes for realistic celestial motion
        // Primary axis: vertical (Y) - azimuthal sweep (~1 rotation per 1 hour)
        // Secondary axis: tilted (X) - slow polar drift (~1 rotation per 3 hours)
        float rotY = elapsedTime * (2.0 * 3.14159265 / 3600.0);
        float rotX = elapsedTime * (2.0 * 3.14159265 / 10800.0);
        float cosY = cos(rotY), sinY = sin(rotY);
        float cosX = cos(rotX), sinX = sin(rotX);
        // Rotate around Y axis, then X axis
        vec3 starDir = viewDir;
        starDir = vec3(cosY * starDir.x + sinY * starDir.z, starDir.y, -sinY * starDir.x + cosY * starDir.z);
        starDir = vec3(starDir.x, cosX * starDir.y - sinX * starDir.z, sinX * starDir.y + cosX * starDir.z);

        // Convert rotated direction to spherical UV coordinates
        float phi = atan(starDir.z, starDir.x);               // azimuth: -PI to PI
        float theta = asin(clamp(-starDir.y, -1.0, 1.0));     // elevation: -PI/2 to PI/2
        vec2 nightSkyUV = vec2(phi / (2.0 * 3.14159265) + 0.5, theta / 3.14159265 + 0.5);
        vec3 nightSkyColor = texture(nightSkyTexture, nightSkyUV).rgb;

        skyColor = mix(skyColor, nightSkyColor, nightSkyBlend);
    }

    // === MOON DISK ===
    if (skyMoonIllumination > 0.001) {
        // Apply the same horizon offset transformation as the sun
        vec3 moonDir = normalize(vec3(skyMoonDir.x, -skyMoonDir.y + horizonOffset, skyMoonDir.z));

        float moonDot = dot(viewDir, moonDir);

        // Daytime transparency: fade moon out as sun rises higher
        // skySunDir.y = sin(altitude): negative below horizon, 0 at horizon, positive above
        // Moon only reaches full opacity when sun is well below horizon (~-10deg = -0.17)
        // Still semi-transparent near the horizon, invisible when sun is high
        float moonDayAlpha = 1.0 - smoothstep(-0.17, 0.5, skySunDir.y);

        if (moonDot > 0.0 && moonDayAlpha > 0.001) {
            // Moon angular radius: ~3.6 degrees diameter = 1.8 degrees half-angle
            // cos(1.8 deg) ≈ 0.99951 — about 1.5x larger than realistic for visual impact
            float moonAngularRadius = 0.99951;
            float edgeWidth = 0.00008;

            // Sharp disk with anti-aliased edge
            float moonDisk = smoothstep(moonAngularRadius - edgeWidth, moonAngularRadius, moonDot);

            if (moonDisk > 0.0) {
                // Calculate local coordinates on the moon disk for phase shape
                // Angular distance from moon center
                float angDist = acos(clamp(moonDot, 0.0, 1.0));
                float moonRadius = acos(moonAngularRadius); // angular radius in radians

                // Normalized position within the moon disk (0 at edge, 1 at center)
                float normDist = 1.0 - angDist / moonRadius;
                normDist = clamp(normDist, 0.0, 1.0);

                // For phase shape, we need a 2D coordinate on the moon face
                // Project view direction onto moon-local coordinate system
                // Create a local frame: moonDir is forward, need up and right
                vec3 moonUp = vec3(0.0, 1.0, 0.0);
                vec3 moonRight = normalize(cross(moonUp, moonDir));
                moonUp = normalize(cross(moonDir, moonRight));

                // Local 2D coordinates on the moon face
                vec3 toView = normalize(viewDir - moonDir * moonDot);
                float localX = dot(toView, moonRight) * angDist / moonRadius;
                float localY = dot(toView, moonUp) * angDist / moonRadius;

                // Phase terminator: illumination maps to terminator position
                // The lit region is where localX < terminatorEdge (sun-facing side)
                // illumination=1 (full): terminatorX = 1, edge at +1 -> all lit
                // illumination=0.5 (half): terminatorX = 0, edge at 0 -> half lit
                // illumination=0 (new): terminatorX = -1, edge at -1 -> all dark
                float terminatorX = 2.0 * skyMoonIllumination - 1.0;

                // The terminator is an ellipse on the disk surface
                float yy = localY * localY;
                float terminatorEdge = terminatorX * sqrt(max(0.0, 1.0 - yy));
                // Widen the smoothstep near the poles where the ellipse curvature is steep
                float edgeSoftness = mix(0.05, 0.35, yy * yy);
                // Smooth the terminator edge — lit when localX is LESS than the edge
                float isLit = smoothstep(terminatorEdge + edgeSoftness, terminatorEdge - edgeSoftness, localX);

                // Limb darkening: edges of the moon are slightly darker
                float limbDarkening = mix(0.7, 1.0, normDist * normDist);

                // Procedural moon surface detail
                vec2 moonUV = vec2(localX, localY) * 3.0 + vec2(50.0, 50.0);
                float surfaceNoise = moonFbm(moonUV);

                // Map noise to brightness: dark maria (~0.65) to bright highlands (~1.05)
                float surfaceBrightness = mix(0.65, 1.05, surfaceNoise);

                // Cool blue tint on darker areas (maria have a subtle blue hue)
                float mariaTint = smoothstep(0.85, 0.65, surfaceBrightness);
                vec3 surfaceColor = mix(vec3(1.0), vec3(0.7, 0.8, 1.0), mariaTint * 0.4);

                vec3 litColor = skyMoonColor * limbDarkening * surfaceBrightness * surfaceColor;
                // Dark side: always opaque (occludes stars). Use the pre-star sky gradient
                // with a subtle moon-colored earthshine so it reads as shadowed lunar surface
                vec3 darkSideMoon = skyColorPreStars + skyMoonColor * 0.02;
                vec3 moonFinalColor = mix(darkSideMoon, litColor, isLit);
                float moonAlpha = moonDisk * moonDayAlpha * moonVisibility;

                skyColor = mix(skyColor, moonFinalColor, moonAlpha);
            }

            // Subtle atmospheric glow around the moon (also faded by daytime transparency)
            float moonGlow = pow(moonDot, 256.0) * 0.05 * skyMoonIllumination * moonDayAlpha * moonVisibility;
            skyColor += skyMoonColor * moonGlow;
        }
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

    // Dithering to eliminate color banding in dark sky gradients
    // Add ±0.5/255 noise per pixel to break up 8-bit quantization bands
    float dither = moonHash(gl_FragCoord.xy) - 0.5;
    skyColor += dither / 255.0;

    FragColor = vec4(skyColor, 1.0);
}
