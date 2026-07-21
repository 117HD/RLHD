#version 330

#include <uniforms/global.glsl>
#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>
#include <utils/starfield.glsl>
#include <utils/aurora.glsl>
#include <utils/sky.glsl>

in vec2 fScreenPos;

out vec4 FragColor;

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
    for (int i = 0; i < 6; i++) {
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

    // Shared gradient math (sun direction, sun-facing/zenith blends, base horizon/zenith color, and sun glow)
    SkyGradient sky = computeSkyGradient(viewDir);
    vec3 skyColor = sky.color;

    // Save sky color before stars are blended in, for opaque moon dark side
    vec3 skyColorPreStars = skyColor;

    // === PROCEDURAL NIGHT SKY ===
    // Fade in the starfield as sun drops below horizon
    // nightFade is already 0 at -15° and 1 at 0°, so we use its inverse
    // starVisibility (from environment override): 0 = no stars (opaque skybox), 1 = full stars
    // Directional starfield blend: stars appear first on the anti-sun side
    // and creep toward the sun-side horizon as twilight deepens
    float baseProgress = 1.0 - sky.nightFade;
    float sunProximity = sky.sunSideBlend * (1.0 - sky.zenithBlend);
    // Night factor shared by the star field and (below) the aurora. The star field
    // additionally scales by starVisibility; the aurora deliberately does NOT, so
    // aurora visibility is controlled independently via auroraVisibility.
    float nightFactor = pow(baseProgress, mix(0.4, 0.9, sunProximity));
    float nightSkyBlend = nightFactor * starVisibility;
    // Precompute starfield rotation (also needed for moon dark side sampling)
    float rotY = elapsedTime * (2.0 * 3.14159265 / 3600.0);
    float rotX = elapsedTime * (2.0 * 3.14159265 / 10800.0);
    float cosY = cos(rotY), sinY = sin(rotY);
    float cosX = cos(rotX), sinX = sin(rotX);
    if (nightSkyBlend > 0.001) {
        // Rotate the star field around two axes for realistic celestial motion
        // Primary axis: vertical (Y) - azimuthal sweep (~1 rotation per 1 hour)
        // Secondary axis: tilted (X) - slow polar drift (~1 rotation per 3 hours)
        vec3 starDir = viewDir;
        starDir = vec3(cosY * starDir.x + sinY * starDir.z, starDir.y, -sinY * starDir.x + cosY * starDir.z);
        starDir = vec3(starDir.x, cosX * starDir.y - sinX * starDir.z, sinX * starDir.y + cosX * starDir.z);

        // Background sky + nebula only. Individual stars are drawn separately as
        // point sprites (star_vert/frag.glsl), so the costly per-pixel star-field
        // search is gone — this just provides the dark night base + nebula.
        vec3 nightSkyColor = proceduralStarfieldBackground(starDir);

        // Fade out the night sky/nebula near the horizon so the sky converges
        // to the plain gradient color that the fog uses, hiding the world edge
        float horizonStarFade = smoothstep(-0.1, 0.07, sky.upAmount);
        skyColor = mix(skyColor, nightSkyColor, nightSkyBlend * horizonStarFade);

        // Shooting stars (atmospheric, use un-rotated viewDir)
        if (viewDir.y < -0.05) {
            skyColor += shootingStars(viewDir, elapsedTime) * nightSkyBlend;
        }
    }

    // === MOON DISK ===
    if (skyMoonIllumination > 0.001) {
        // Apply the same horizon offset transformation as the sun
        vec3 moonDir = normalize(vec3(skyMoonDir.x, -skyMoonDir.y + HORIZON_OFFSET, skyMoonDir.z));

        float moonDot = dot(viewDir, moonDir);

        // Daytime transparency: fade moon out as sun rises higher
        // skySunDir.y = sin(altitude): negative below horizon, 0 at horizon, positive above
        // Moon only reaches full opacity when sun is well below horizon (~-10deg = -0.17)
        // Still semi-transparent near the horizon, invisible when sun is high
        float moonDayAlpha = 1.0 - smoothstep(-0.17, 0.5, skySunDir.y);

        // Fade moon when it's close to the sun in the sky
        float sunMoonDot = dot(moonDir, sky.sunDir);
        float sunProximityFade = smoothstep(0.9, 0.7, sunMoonDot);
        moonDayAlpha *= sunProximityFade;

        if (moonDot > 0.0 && moonDayAlpha > 0.001) {
            // Moon angular radius: ~3.8 degrees diameter = 1.9 degrees half-angle
            // cos(1.9 deg) ≈ 0.99945 — enlarged beyond realistic for visual impact.
            // Scale the angular radius by moonSizeMult, then convert back to a cosine
            // threshold, so the per-environment size multiplier grows/shrinks the disk.
            float moonBaseRadius = acos(0.99945);
            float moonAngularRadius = cos(moonBaseRadius * moonSizeMult);
            float edgeWidth = moonDot > 0.01 ? fwidth(moonDot) * 1.5 : 0;

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
                float limbDarkening = mix(0.85, 1.0, normDist);

                // Procedural moon surface detail
                vec2 moonUV = vec2(localX, localY) * 4.0 + vec2(50.0, 50.0);

                // Large-scale terrain — broad tonal variation
                float largeTerrain = moonFbm(moonUV * 0.4);

                // Medium-scale detail
                float medTerrain = moonFbm(moonUV * 1.5);

                // Fine surface texture
                float fineTerrain = moonFbm(moonUV * 5.0);

                // Base brightness from blended terrain layers
                float surfaceBrightness = largeTerrain * 0.4 + medTerrain * 0.4 + fineTerrain * 0.2;
                surfaceBrightness = mix(0.6, 1.1, surfaceBrightness);

                // Dark maria (seas) — a few subtle darker patches
                float seaNoise = moonFbm(moonUV * 0.8 + vec2(30.0, 70.0));
                float seaMask = smoothstep(0.50, 0.40, seaNoise);
                surfaceBrightness *= mix(1.0, 0.88, seaMask);

                // Crater ray systems — bright ejecta from impact sites
                vec2 rayCenters[3] = vec2[3](
                    vec2(51.0, 47.5),   // lower right
                    vec2(48.2, 51.0),   // upper left
                    vec2(50.5, 50.8)    // center-right
                );
                float rayMaxDist[3] = float[3](2.8, 2.0, 1.6);
                float craterSize[3] = float[3](0.10, 0.08, 0.07);
                for (int ri = 0; ri < 3; ri++) {
                    vec2 toPoint = moonUV - rayCenters[ri];
                    float dist = length(toPoint);
                    // Bright crater center
                    float centerBright = smoothstep(craterSize[ri], craterSize[ri] * 0.2, dist) * 0.15;
                    // Dark rim around crater
                    float darkRim = smoothstep(craterSize[ri] * 0.7, craterSize[ri], dist)
                                  * (1.0 - smoothstep(craterSize[ri], craterSize[ri] * 1.5, dist));
                    surfaceBrightness += centerBright;
                    surfaceBrightness -= darkRim * 0.08;
                    // Ejecta: diffuse bright halo + spiderweb detail near crater
                    if (dist > craterSize[ri] * 0.8 && dist < rayMaxDist[ri]) {
                        float distFade = 1.0 - smoothstep(craterSize[ri], rayMaxDist[ri], dist);
                        // Diffuse bright halo around the crater
                        float halo = distFade * distFade * 0.08;
                        // Spiderweb near-field: high-freq noise for irregular bright webbing
                        float nearDist = smoothstep(craterSize[ri] * 1.5, craterSize[ri] * 10.0, dist);
                        float webNoise = moonFbm(moonUV * 6.0 + vec2(float(ri) * 17.0));
                        float webNoise2 = moonFbm(moonUV * 10.0 + vec2(float(ri) * 31.0));
                        float web = (smoothstep(0.42, 0.62, webNoise) + smoothstep(0.45, 0.65, webNoise2) * 0.6)
                                  * (1.0 - nearDist) * distFade * 0.12;
                        // Long rays: wobbly lines using noise offset on perpendicular distance
                        float rayBright = 0.0;
                        for (int rj = 0; rj < 14; rj++) {
                            float rayAngle = moonHash(vec2(float(ri) * 7.0 + float(rj) * 13.0, float(rj) * 3.0 + float(ri) * 11.0)) * 6.2832;
                            vec2 rayDir = vec2(cos(rayAngle), sin(rayAngle));
                            float along = dot(toPoint, rayDir);
                            if (along > 0.0) {
                                // Wobble the ray path with noise
                                float wobble = (moonNoise(vec2(along * 3.0 + float(ri) * 20.0, float(rj) * 5.0)) - 0.5) * 0.06;
                                float perpDist = abs(toPoint.x * rayDir.y - toPoint.y * rayDir.x + wobble);
                                float rayW = 0.025 + moonNoise(vec2(float(rj) * 9.0, float(ri) * 4.0)) * 0.015;
                                float ray = smoothstep(rayW, rayW * 0.2, perpDist);
                                // Vary brightness per ray
                                float rayIntensity = 0.5 + moonHash(vec2(float(rj) * 11.0, float(ri) * 6.0)) * 0.5;
                                rayBright = max(rayBright, ray * rayIntensity);
                            }
                        }
                        surfaceBrightness += rayBright * distFade * 0.09 + halo + web;
                    }
                }

                // Warm gray color that shifts subtly with brightness
                // Darker areas slightly warmer, brighter areas slightly cooler
                float colorBlend = smoothstep(0.7, 0.95, surfaceBrightness);
                vec3 darkTone = vec3(0.85, 0.83, 0.79);
                vec3 brightTone = vec3(1.0, 0.98, 0.95);
                vec3 surfaceColor = mix(darkTone, brightTone, colorBlend);

                vec3 litColor = skyMoonColor * limbDarkening * surfaceBrightness * surfaceColor;
                // Dark side: opaque (occludes stars) but matches surrounding sky tone.
                // When stars are visible, blend toward the starfield background color
                // (without star points) so the dark side doesn't glow brighter than the sky.
                vec3 darkSideBase = skyColorPreStars;
                if (nightSkyBlend > 0.001) {
                    vec3 moonStarDir = viewDir;
                    moonStarDir = vec3(cosY * moonStarDir.x + sinY * moonStarDir.z, moonStarDir.y, -sinY * moonStarDir.x + cosY * moonStarDir.z);
                    moonStarDir = vec3(moonStarDir.x, cosX * moonStarDir.y - sinX * moonStarDir.z, sinX * moonStarDir.y + cosX * moonStarDir.z);
                    vec3 nightBgColor = proceduralStarfieldBackground(moonStarDir);
                    darkSideBase = mix(skyColorPreStars, nightBgColor, nightSkyBlend);
                }
                vec3 darkSideMoon = darkSideBase + skyMoonColor * 0.02;
                vec3 moonFinalColor = mix(darkSideMoon, litColor, isLit);
                // Fade moon near the horizon to match the star/nebula horizon fade
                float moonHorizonFade = smoothstep(-0.1, 0.07, sky.upAmount);
                float moonAlpha = moonDisk * moonDayAlpha * moonVisibility * moonHorizonFade;

                skyColor = mix(skyColor, moonFinalColor, moonAlpha);
            }

            // Subtle atmospheric glow around the moon (also faded by daytime transparency).
            // Divide the falloff exponent by moonSizeMult so the glow widens with a
            // larger moon (and tightens with a smaller one), matching the disk.
            float glowHorizonFade = smoothstep(-0.1, 0.07, sky.upAmount);
            float moonGlow = pow(moonDot, 256.0 / max(moonSizeMult, 0.001)) * 0.05 * skyMoonIllumination * moonDayAlpha * moonVisibility * glowHorizonFade;
            skyColor += skyMoonColor * moonGlow;
        }
    }

    // Aurora borealis — animated curtains near the northern horizon. Shown on the
    // randomly-selected aurora nights and faded with the night, but decoupled from
    // starVisibility so it can be scaled independently per environment via
    // auroraVisibility (0 on non-aurora nights or aurora-hidden areas). Uses
    // nightFactor (the star-independent night fade) in place of the previous
    // nightSkyBlend so it no longer disappears when starVisibility is 0.
    // Drawn AFTER the moon disk so the aurora composites visually in front of the moon.
    if (auroraVisibility > 0.001 && nightFactor > 0.001) {
        skyColor += proceduralAurora(viewDir, elapsedTime) * nightFactor * auroraVisibility;
    }

    skyColor = applySkyHaze(skyColor, sky.upAmount, sky.sunSideBlend, sky.zenithBlend);

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