#version 330

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>
#include <utils/output_transform.glsl>

#include <utils/misc.glsl>
#include <utils/starfield.glsl>
#include <utils/aurora.glsl>
#include <utils/sky.glsl>
#include <utils/sky_fog.glsl>

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
        st *= 2;
        amplitude *= 0.5;
    }
    return value;
}

float nightSkyHorizonFade(float upAmount, float horizonShift) {
    return smoothstep(-0.1 + horizonShift, 0.07 + horizonShift, upAmount);
}

void main() {
    // Unproject a near/far ray to get the view direction.
    vec4 nearClip = vec4(fScreenPos, -1.0, 1.0);
    vec4 farClip = vec4(fScreenPos, 1.0, 1.0);

    vec4 nearWorld = invProjectionMatrix * nearClip;
    vec4 farWorld = invProjectionMatrix * farClip;

    nearWorld /= nearWorld.w;
    farWorld /= farWorld.w;

    vec3 viewDir = normalize(farWorld.xyz - nearWorld.xyz);

    SkyGradient sky = computeSkyGradient(viewDir);
    vec3 skyColor = sky.color;
    // Keep a star-free sky reference for opaque celestial bodies. This lets the
    // moon cover stars without allowing the day gradient to show through it.
    vec3 skyColorPreStars = skyColor;

    // Shift the shared night-sky horizon line.
    float horizonShift = nightHorizonOffset(starHorizonHeight);

    // Stars appear first opposite the sun, then spread across the low-sun sky.
    float baseProgress = 1.0 - sky.nightFade;
    float sunProximity = sky.sunSideBlend * (1.0 - sky.zenithBlend);
    // Aurora visibility is independent of the night-sky background.
    float nightFactor = pow(baseProgress, mix(0.4, 0.9, sunProximity));
    float skyBlend = nightFactor;
    float starBlend = nightFactor * starVisibility;
    vec3 shootingStarColor = vec3(0.0);
    if (skyBlend > 0.001) {
        // Individual stars are drawn separately as point sprites.
        vec3 nightSkyColor = nightSkyBackground(viewDir, elapsedTime);

        // Converge to the fog-matched gradient at the horizon.
        float horizonStarFade = nightSkyHorizonFade(sky.upAmount, horizonShift);
        skyColor = blendSkyBackground(skyColor, nightSkyColor, skyBlend * horizonStarFade);
    }
    // Shooting stars are atmospheric and render in front of the moon.
    if (starBlend > 0.001 && -viewDir.y > 0.05 + horizonShift)
        shootingStarColor = shootingStars(viewDir, elapsedTime) * starBlend;

    // Match the moon's default apparent size. Reuse the authored sun-glow color
    // so environmental suppression and sunset colors still apply.
    float sunDot = dot(viewDir, sky.sunDir);
    float sunRadius = acos(0.99945);
    float sunEdge = cos(sunRadius);
    float sunAntialias = max(fwidth(sunDot), 1e-7);
    float sunDisk = smoothstep(sunEdge - sunAntialias, sunEdge + sunAntialias, sunDot);
    float sunHorizon = smoothstep(-0.002, 0.002, -viewDir.y + HORIZON_OFFSET);
    float sunMu = sqrt(clamp((sunDot - sunEdge) / (1.0 - sunEdge), 0.0, 1.0));
    // Mild limb darkening; the moon is composited afterward and can cover the sun.
    skyColor += skySunColor * sunDisk * sunHorizon * mix(0.6, 1.0, sunMu);

    // Render the moon disk
    if (moonVisibility > 0.001) {
        // Apply the sun's perceived-horizon offset.
        vec3 moonDir = normalize(vec3(skyMoonDir.x, -skyMoonDir.y + HORIZON_OFFSET, skyMoonDir.z));
        vec3 moonSunDir = normalize(vec3(skyMoonPhaseLightDirection.x, -skyMoonPhaseLightDirection.y + HORIZON_OFFSET, skyMoonPhaseLightDirection.z));

        float moonDot = dot(viewDir, moonDir);

        // Daylight lowers lunar contrast rather than making the disk transparent.
        // Scale against the local sky brightness so the moon remains subtly visible
        // in daytime, but naturally becomes prominent as the sky darkens.
        float skyLuminance = linearSrgbLuminance(skyColorPreStars);
        float moonDayVisibility = 1.0 / (1.0 + skyLuminance * 12.0);

        // Fade the moon near the sun.
        float sunMoonDot = dot(moonDir, sky.sunDir);
        float sunProximityFade = smoothstep(0.9, 0.7, sunMoonDot);
        moonDayVisibility *= sunProximityFade;

        if (moonDot > 0.0 && moonDayVisibility > 0.001) {
            // Deliberately enlarged ~1.9° moon radius, scaled per environment.
            float moonBaseRadius = acos(0.99945);
            float moonAngularRadius = cos(moonBaseRadius * moonSizeMult);
            float edgeWidth = moonDot > 0.01 ? fwidth(moonDot) * 1.5 : 0;

            float moonDisk = smoothstep(moonAngularRadius - edgeWidth, moonAngularRadius, moonDot);
            if (moonDisk > 0.0) {
                // Moon-local coordinates for the phase shape.
                float angDist = acos(clamp(moonDot, 0.0, 1.0));
                float moonRadius = acos(moonAngularRadius); // angular radius in radians

                // Use a fallback reference axis near vertical to avoid a zero cross product.
                vec3 moonUp = abs(moonDir.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
                vec3 moonRight = normalize(cross(moonUp, moonDir));
                moonUp = normalize(cross(moonDir, moonRight));

                vec3 toView = normalize(viewDir - moonDir * moonDot);
                float localX = dot(toView, moonRight) * angDist / moonRadius;
                float localY = dot(toView, moonUp) * angDist / moonRadius;

                // Orient the terminator toward the sun; the mirrored moon can still use its own phase.
                vec2 moonToSun = vec2(dot(moonSunDir, moonRight), dot(moonSunDir, moonUp));
                float moonToSunLength = length(moonToSun);
                moonToSun = moonToSunLength > 1e-4 ? moonToSun / moonToSunLength : vec2(1.0, 0.0);
                moonToSun *= skyMoonPhaseReversed > 0.5 ? -1.0 : 1.0;

                vec2 moonLocal = vec2(localX, localY);
                float moonLocalZ = sqrt(max(0.0, 1.0 - dot(moonLocal, moonLocal)));
                vec3 moonSurfaceNormal = vec3(moonLocal, moonLocalZ);
                float phaseCos = 2.0 * skyMoonIllumination - 1.0;
                float phaseSin = sqrt(max(0.0, 1.0 - phaseCos * phaseCos));
                vec3 moonLightDir = vec3(moonToSun * phaseSin, phaseCos);

                // Libration moves surface detail without rotating the sun-facing terminator.
                vec2 moonSurface = moonLocal + skyMoonLibration * (2.0 / PI);
                float librationRoll = (skyMoonLibration.x + skyMoonLibration.y) * 0.25;
                float librationRollCos = cos(librationRoll);
                float librationRollSin = sin(librationRoll);
                mat2 librationRotation = mat2(
                    librationRollCos, -librationRollSin,
                    librationRollSin, librationRollCos
                );
                vec2 moonDetail = librationRotation * moonSurface;
                vec2 moonUV = moonDetail * 4.0 + vec2(50.0, 50.0);

                // Large-scale terrain - broad tonal variation
                float largeTerrain = moonFbm(moonUV * 0.4);

                // Medium-scale detail
                float medTerrain = moonFbm(moonUV * 1.5);

                // Fine surface texture
                float fineTerrain = moonFbm(moonUV * 5.0);

                // Base brightness from blended terrain layers
                float surfaceNoise = largeTerrain * 0.4 + medTerrain * 0.4 + fineTerrain * 0.2;
                surfaceNoise = mix(0.6, 1, surfaceNoise);

                // Dark maria (seas) - a few subtle darker patches
                float seaNoise = moonFbm(moonUV * 0.8 + vec2(30.0, 70.0));
                float seaMask = smoothstep(0.50, 0.40, seaNoise);
                surfaceNoise *= mix(1.0, 0.88, seaMask);

                vec2 impactPositions[3] = vec2[3](
                    vec2(0.6, -0.25),
                    vec2(-0.25, -0.1),
                    vec2(0.55, 0.55)
                );
                float impactMaxDistance[3] = float[3](1.8, 2.0, 2.8);
                float impactRadius[3] = float[3](0.10, 0.1, 0.11);
                float impactHighlight = 0.0;
                for (int impact = 0; impact < 3; impact++) {
                    vec2 impactDetail = impactPositions[impact];
                    vec2 impactLocal = transpose(librationRotation) * impactDetail - skyMoonLibration * 2.0 / PI;
                    float impactLocalZ = sqrt(max(0.0, 1.0 - dot(impactLocal, impactLocal)));
                    vec3 impactNormal = vec3(impactLocal, impactLocalZ);
                    float distanceFromImpact = acos(clamp(dot(moonSurfaceNormal, impactNormal), -1.0, 1.0)) * 4.0;
                    float ejectaStartFade = smoothstep(
                        impactRadius[impact] * 0.2,
                        impactRadius[impact] * 0.8,
                        distanceFromImpact
                    );
                    if (distanceFromImpact >= impactMaxDistance[impact])
                        continue;

                    float distanceFade = 1.0 - smoothstep(impactRadius[impact], impactMaxDistance[impact], distanceFromImpact);
                    float impactEdgeWidth = fwidth(distanceFromImpact) * 1.5;
                    float ejectaFade = smoothstep(
                        impactRadius[impact] * 0.8 - impactEdgeWidth,
                        impactRadius[impact] * 0.8 + impactEdgeWidth,
                        distanceFromImpact
                    );
                    float halo = distanceFade * distanceFade * 0.08;
                    float nearImpact = smoothstep(impactRadius[impact] * 1.5, impactRadius[impact] * 10.0, distanceFromImpact);
                    float webNoise = moonFbm(moonUV * 6.0 + vec2(float(impact) * 17.0));
                    float webNoise2 = moonFbm(moonUV * 10.0 + vec2(float(impact) * 31.0));
                    float webbing = (smoothstep(0.42, 0.62, webNoise) + smoothstep(0.45, 0.65, webNoise2) * 0.6) *
                        (1.0 - nearImpact) * distanceFade * 0.12;
                    float impactRays = 0.0;
                    vec3 impactEast = vec3(impactNormal.z, 0.0, -impactNormal.x);
                    float impactEastLength = length(impactEast);
                    impactEast = impactEastLength > 1e-4 ? impactEast / impactEastLength : vec3(1.0, 0.0, 0.0);
                    vec3 impactNorth = normalize(cross(impactNormal, impactEast));
                    for (int ray = 0; ray < 14; ray++) {
                        float rayAngle = moonHash(vec2(
                            float(impact) * 7.0 + float(ray) * 13.0,
                            float(ray) * 3.0 + float(impact) * 11.0
                        )) * 6.2832;
                        vec3 rayDirection = impactEast * cos(rayAngle) + impactNorth * sin(rayAngle);
                        float alongRay = atan(
                            dot(moonSurfaceNormal, rayDirection),
                            dot(moonSurfaceNormal, impactNormal)
                        ) * 4.0;
                        if (alongRay <= 0.0)
                            continue;

                        float wobble = (moonNoise(vec2(
                            alongRay * 3.0 + float(impact) * 20.0,
                            float(ray) * 5.0
                        )) - 0.5) * 0.06;
                        vec3 rayPlaneNormal = cross(impactNormal, rayDirection);
                        float perpendicularDistance = abs(
                            asin(clamp(dot(moonSurfaceNormal, rayPlaneNormal), -1.0, 1.0)) * 4.0 + wobble
                        );
                        float rayWidth = 0.025 + moonNoise(vec2(float(ray) * 9.0, float(impact) * 4.0)) * 0.015;
                        float rayLine = smoothstep(rayWidth, rayWidth * 0.2, perpendicularDistance);
                        float rayIntensity = 0.5 + moonHash(vec2(float(ray) * 11.0, float(impact) * 6.0)) * 0.5;
                        impactRays = max(impactRays, rayLine * rayIntensity);
                    }
                    impactHighlight = max(impactHighlight, saturate(
                        (impactRays * distanceFade * 0.07 + halo + webbing) * ejectaStartFade * ejectaFade * 8.0
                    ));
                }

                float lambert = dot(moonSurfaceNormal, moonLightDir);
                float terminatorJitter = (surfaceNoise - 0.85) * 0.01 + (fineTerrain - 0.5) * 0.03;
                // Surface relief only perturbs incidence near the terminator.
                float terminatorRoughness =
                    (1.0 - smoothstep(0.05, 0.35, abs(lambert))) *
                    smoothstep(0.05, 0.25, moonLocalZ);
                float roughLambert = lambert + terminatorJitter * terminatorRoughness;
                float lightCos = max(roughLambert, 0.0);
                float viewCos = moonLocalZ;
                // Lunar regolith scatters closer to Lommel-Seeliger than ideal Lambertian diffuse.
                float lommelSeeliger = 2.0 * lightCos / max(lightCos + viewCos, 1e-4);
                float lunarLambertWeight = mix(0.6, 0.15, max(phaseCos, 0.0));
                float lunarDiffuse = mix(lommelSeeliger, lightCos, lunarLambertWeight);
                float terminatorFade = smoothstep(-0.14, 0.08, roughLambert);
                float isLit = skyMoonIllumination < 0.001 ? 0.0 : clamp(lunarDiffuse, 0.0, 1.0) * terminatorFade;
                float terminatorProximity = 1.0 - smoothstep(0.02, 0.2, abs(roughLambert));
                float crescentEdgeFade = smoothstep(0.0, 0.25, moonLocalZ);
                isLit *= mix(1.0, crescentEdgeFade, terminatorProximity);

                // Keep surface contrast below the output's clipping threshold.
                // Ejecta raise reflectance toward the same peak.
                float surfaceContrast = smoothstep(0.65, 0.95, surfaceNoise);
                float surfaceDetail = mix(0.12, 0.48, surfaceContrast);
                surfaceDetail = mix(surfaceDetail, 0.48, impactHighlight * 0.8);
                vec3 moonBrightSide = skyMoonDiskColor * surfaceDetail;

                // The opaque disk occludes stars and nebulas while its dark side matches the night sky.
                vec3 moonDarkSide = skyColorPreStars;
                if (skyBlend > 0.001) {
                    float horizonStarFade = nightSkyHorizonFade(sky.upAmount, horizonShift);
                    moonDarkSide = blendSkyBackground(moonDarkSide, STARFIELD_BACKGROUND_COLOR, skyBlend * horizonStarFade);
                }

                // Keep the disk opaque so stars and the sky gradient cannot show through crescents.
                vec3 moonColor = mix(moonDarkSide, moonBrightSide, isLit * moonDayVisibility);

                // Fade moon near the horizon to match the star/nebula horizon fade
                float moonHorizonFade = nightSkyHorizonFade(sky.upAmount, horizonShift);
                float moonAlpha = moonDisk * moonVisibility * moonHorizonFade;

                skyColor = mix(skyColor, moonColor, moonAlpha);
            }

            // Cheap bloom-like halo, biased toward the illuminated side of a crescent.
            float rimDistance = max(0.0, acos(clamp(moonDot, 0.0, 1.0)) /
                max(moonBaseRadius * moonSizeMult, 0.001) - 1.0);
            vec3 toRim = viewDir - moonDir * moonDot;
            vec3 toLight = moonSunDir - moonDir * dot(moonSunDir, moonDir);
            float litSide = dot(toRim, toLight) / max(length(toRim) * length(toLight), 1e-5);
            litSide *= skyMoonPhaseReversed > 0.5 ? -1.0 : 1.0;
            float phaseCos = 2.0 * skyMoonIllumination - 1.0;
            float phaseWeight = mix(1.0, smoothstep(-0.2, 0.5, litSide), sqrt(max(0.0, 1.0 - phaseCos * phaseCos)));
            float halo = 0.0015 * exp(-8.0 * rimDistance * rimDistance) + 0.00015 * exp(-2.0 * rimDistance);
            halo *= (1.0 - moonDisk) * skyMoonIllumination * phaseWeight * moonDayVisibility * moonVisibility;
            skyColor += skyMoonDiskColor * halo * nightSkyHorizonFade(sky.upAmount, horizonShift);
        }
    }

    skyColor += shootingStarColor;

    // Auroras lose contrast against a bright sky much sooner than the moon.
    float auroraContrast = 1.0 / (1.0 + linearSrgbLuminance(skyColorPreStars) * 1200.0);
    float auroraStrength = nightFactor * auroraVisibility * auroraContrast;
    if (auroraStrength > 0.001)
        skyColor += proceduralAurora(viewDir, elapsedTime) * auroraStrength;

    skyColor = applySkyFog(skyColor, sky.upAmount);
    skyColor = applyColorAdjustments(linearToSrgb(skyColor));
    skyColor = applyOutputCorrection(skyColor);
    float dither = moonHash(gl_FragCoord.xy) - 0.5;
    FragColor = vec4(skyColor + dither / 255.0, 1.0);
}
