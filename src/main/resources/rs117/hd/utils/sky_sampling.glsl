#pragma once

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/misc.glsl>
#include <utils/sky.glsl>
#include <utils/aurora.glsl>
#include <utils/tone_mapping.glsl>
#include <utils/moon.glsl>

// Compress only the disk contribution, leaving the surrounding sky unchanged.
// A neutral background prevents sunset fog from shifting the disk's hue.
vec3 toneMapSkyDisk(vec3 light, vec3 background) {
    vec3 neutralBackground = vec3(linearSrgbLuminance(background));
    vec3 mappedBackground = softClipColor(tonemap_hue_preserving(neutralBackground));
    vec3 mappedDisk = softClipColor(tonemap_hue_preserving(neutralBackground + light));
    return max(mappedDisk - mappedBackground, vec3(0.0));
}

struct SkySample {
    vec3 background;
    vec3 sun;
    vec3 moon;
};

// Fragment-stage sky sampling. viewDir is a unit world direction, with negative Y up.
// Returns separate linear sRGB contributions before the output transform. toneMap enables the
// disks' selective tone map; false preserves linear HDR for reflection/composition.
// Individual stars are rendered separately as sprites.
// Camera projection correction is for direct viewing only, never reflected rays.
// diskViewDir can follow stronger waves than viewDir for artistic water reflections.
// roughness is the RMS slope of unresolved waves; normal is their mean normal.
SkySample sampleSky(vec3 viewDir, vec3 diskViewDir, bool correctProjection, bool toneMap, vec3 normal, float roughness) {
    if (!uboSky.enabled)
        return SkySample(fogColor, vec3(0.0), vec3(0.0));

    SkyGradient sky = computeSkyGradient(viewDir);
    // A slope perturbation rotates the reflected ray by 2*slope in the
    // incidence plane, and by 2*slope*cos(incidence) across it. Projection
    // onto the water then produces the long reflection at grazing angles.
    vec2 diskBlur = 2.0 * roughness * vec2(abs(dot(diskViewDir, normal)), 1.0);
    vec3 blurAxis = normal - diskViewDir * dot(normal, diskViewDir);
    vec3 skyColor = sky.color;
    float fogTransmittance = skyFogTransmittance(sky.upAmount);
    float diskUpAmount = -diskViewDir.y;
    float diskFogTransmittance = skyFogTransmittance(diskUpAmount);
    vec3 moonHalo = vec3(0.0);
    // Keep a star-free sky reference for opaque celestial bodies. This lets the
    // moon cover stars without allowing the day gradient to show through it.
    vec3 skyColorPreStars = skyColor;

    // Shift the shared night-sky horizon line.
    float horizonShift = nightHorizonOffset(uboSky.starHorizonHeight);

    // Stars appear first opposite the sun, then spread across the low-sun sky.
    // Aurora visibility is independent of the night-sky background.
    float nightFactor = nightSkyBlend(sky);
    float skyBlend = nightFactor;
    #if STAR_MODE != STAR_MODE_OFF
        float starBlend = nightFactor * uboSky.starVisibility;
    #endif
    vec3 shootingStarColor = vec3(0.0);
    // Individual stars are drawn separately as point sprites.
    skyColor = visibleSkyColor(sky, viewDir, elapsedTime);
    // Shooting stars are atmospheric and render in front of the moon.
    #if STAR_MODE != STAR_MODE_OFF
        if (starBlend > 0.001 && -viewDir.y > 0.05 + horizonShift)
            shootingStarColor = shootingStars(viewDir, elapsedTime) * starBlend;
    #endif

    float sunDot = dot(correctProjection ? celestialViewDirection(diskViewDir, sky.sunDir) : diskViewDir, sky.sunDir);
    float sunRadius = acos(0.99945);
    float sunEdge = cos(sunRadius);
    float sunAntialias = max(fwidth(sunDot), 1e-7);
    float sunDisk = smoothstep(sunEdge - sunAntialias, sunEdge + sunAntialias, sunDot);
    float sunBlurBlend = smoothstep(0.0, sunRadius * 0.5, diskBlur.y);
    if (roughness > 0.0) {
        vec3 local;
        // A full phase gives the same Gaussian disk coverage used for the moon.
        vec2 coverage = blurredMoonDisk(local, diskViewDir, sky.sunDir, sky.sunDir,
            1.0, sunRadius, diskBlur, blurAxis);
        sunDisk = mix(sunDisk, coverage.x, sunBlurBlend);
    }
    // Fade the sun gradually into the horizon
    float sunHorizon = smoothstep(-0.09, 0.04, diskUpAmount + HORIZON_OFFSET);
    sunHorizon *= smoothstep(sin(radians(-4.0)), sin(radians(-0.5)), uboSky.sunDir.y);
    float sunMu = sqrt(clamp((sunDot - sunEdge) / (1.0 - sunEdge), 0.0, 1.0));
    // Separate disk intensity from the authored atmospheric glow. Artistic scale,
    // not a physical solar radiance calibration; kept here for shader hot reload.
    float sunDiskStrength = 20;
    vec3 sunLight = uboSky.sunColor * sunDiskStrength * diskFogTransmittance *
        mix(mix(0.6, 1.0, sunMu), 0.866667, sunBlurBlend);
    if (toneMap)
        sunLight = toneMapSkyDisk(sunLight, applySkyFog(skyColor, fogTransmittance));
    // Apply coverage after compression, as for the moon, to retain soft edges.
    sunLight *= sunDisk * sunHorizon;

    // Apply the same perceived-horizon offset as the sun.
    vec3 moonDir = normalize(vec3(uboSky.moonDir.x, -uboSky.moonDir.y + HORIZON_OFFSET, uboSky.moonDir.z));
    vec3 moonCompositeColor = vec3(0.0);
    vec3 moonDiskLight = vec3(0.0);
    float moonCompositeAlpha = 0.0;

    // Render the moon disk
    if (uboSky.moonVisibility > 0.001) {
        vec3 moonIlluminationDir = normalize(vec3(uboSky.moonSurfaceLightDirection.x, -uboSky.moonSurfaceLightDirection.y + HORIZON_OFFSET, uboSky.moonSurfaceLightDirection.z));

        vec3 moonViewDir = correctProjection ? celestialViewDirection(diskViewDir, moonDir) : diskViewDir;
        float moonDot = dot(moonViewDir, moonDir);

        // Suppress lunar contrast only near overlap of the artistically enlarged disks.
        const float moonBaseRadius = 0.03317f;
        float overlapRadius = sunRadius + moonBaseRadius * uboSky.moonSizeMult;
        float sunMoonDot = dot(moonDir, sky.sunDir);
        float sunProximityFade = 1.0 - smoothstep(cos(overlapRadius * 1.1), cos(overlapRadius * 0.5), sunMoonDot);
        float moonDayVisibility = sunProximityFade;

        if (moonDot > 0.0 && moonDayVisibility > 0.001) {
            // Deliberately enlarged ~1.9° moon radius, scaled per environment.
            float moonAngularRadius = cos(moonBaseRadius * uboSky.moonSizeMult);
            float edgeWidth = moonDot > 0.01 ? max(fwidth(moonDot) * 1.5, 1e-7) : 0;

            // Keep the antialiased edge within the sphere used for surface shading.
            float moonDisk = smoothstep(moonAngularRadius, moonAngularRadius + edgeWidth, moonDot);
            float sharpMoonDisk = moonDisk;
            float moonBlurBlend = smoothstep(0.0, max(moonBaseRadius * uboSky.moonSizeMult * 0.5, 1e-5), diskBlur.y);
            vec3 blurredLocal = vec3(0.0);
            vec2 blurredCoverage = vec2(0.0);
            if (roughness > 0.0) {
                blurredCoverage = blurredMoonDisk(blurredLocal, diskViewDir, moonDir, moonIlluminationDir,
                    2.0 * uboSky.moonIllumination - 1.0,
                    max(moonBaseRadius * uboSky.moonSizeMult, 1e-5), diskBlur, blurAxis);
                moonDisk = mix(moonDisk, blurredCoverage.x, moonBlurBlend);
            }
            if (moonDisk > 0.0) {
                // Moon-local coordinates for the phase shape.
                float angDist = acos(clamp(moonDot, 0.0, 1.0));
                float moonRadius = acos(moonAngularRadius); // angular radius in radians

                // Use a fallback reference axis near vertical to avoid a zero cross product.
                vec3 moonUp = abs(moonDir.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
                vec3 moonRight = normalize(cross(moonUp, moonDir));
                moonUp = normalize(cross(moonDir, moonRight));

                vec3 toView = moonViewDir - moonDir * moonDot;
                toView /= max(length(toView), 1e-7);
                float localX = dot(toView, moonRight) * angDist / moonRadius;
                float localY = dot(toView, moonUp) * angDist / moonRadius;

                // Orient the terminator toward the resolved illuminant.
                vec2 moonToLight = vec2(dot(moonIlluminationDir, moonRight), dot(moonIlluminationDir, moonUp));
                float moonToLightLength = length(moonToLight);
                moonToLight = moonToLightLength > 1e-4 ? moonToLight / moonToLightLength : vec2(1.0, 0.0);

                vec2 moonLocal = vec2(localX, localY);
                float moonLocalZ = sqrt(max(0.0, 1.0 - dot(moonLocal, moonLocal)));
                vec3 moonSurfaceNormal = vec3(moonLocal, moonLocalZ);
                float phaseCos = 2.0 * uboSky.moonIllumination - 1.0;
                float phaseSin = sqrt(max(0.0, 1.0 - phaseCos * phaseCos));
                vec3 moonLightDir = vec3(moonToLight * phaseSin, phaseCos);

                // Sample detail by angular distance across the hemisphere so it
                // becomes progressively foreshortened toward the moon's limb.
                float moonSurfaceRadius = length(moonLocal);
                vec2 moonSurface = moonLocal;
                if (moonSurfaceRadius > 1e-4)
                    moonSurface *= asin(min(moonSurfaceRadius, 1.0)) / moonSurfaceRadius;

                // Libration moves surface detail without rotating the terminator.
                moonSurface += uboSky.moonLibration * (2.0 / PI);
                float librationRoll = (uboSky.moonLibration.x + uboSky.moonLibration.y) * 0.25;
                float librationRollCos = cos(librationRoll);
                float librationRollSin = sin(librationRoll);
                mat2 librationRotation = mat2(
                    librationRollCos, -librationRollSin,
                    librationRollSin, librationRollCos
                );
                vec2 moonDetail = librationRotation * moonSurface;
                vec2 moonUV = moonDetail * 4.0 + vec2(50.0, 50.0);

                float noiseTerrain = 0.5;
                float surfaceNoise = 1.0;
                if (moonBlurBlend < 1.0) {
                    // Large-scale terrain - broad tonal variation
                    float largeTerrain = moonFbm(moonUV * 0.5);

                    // Medium-scale detail
                    float medTerrain = moonFbm(moonUV * 1.5);

                    // Fine surface texture
                    float fineTerrain = moonFbm(moonUV * 3);

                    noiseTerrain = moonFbm(moonUV * 51);

                    // Base brightness from blended terrain layers
                    vec4 portion = vec4(0.48, 0.27, 0.17, 0.07);
                    surfaceNoise = dot(vec4(largeTerrain, medTerrain, fineTerrain, noiseTerrain), portion);
                    surfaceNoise = mix(0.6, 1, surfaceNoise);

                    // Dark maria (seas) - a few subtle darker patches
                    float seaNoise = moonFbm(moonUV * 0.45 + vec2(9.8, 5.6));
                    float seaSpan = 0.1;
                    float seaStart = 0.377;
                    float seaMask = smoothstep(seaStart + seaSpan, seaStart, seaNoise);
                    surfaceNoise *= mix(1.0, 0.88, seaMask);

                    vec2 impactPositions[3] = vec2[3](
                        vec2(0.537, 0.651),
                        vec2(-0.208, -0.286),
                        vec2(-0.263, 0.576)
                    );
                    float impactMaxDistance[3] = float[3](1.5, 1.8, 3.5);
                    float impactRadius[3] = float[3](0.10, 0.1, 0.11);
                    float impactHighlight = 0.0;
                    for (int impact = 0; impact < 3; impact++) {
                        vec2 impactDetail = impactPositions[impact];
                        vec2 impactLocal = transpose(librationRotation) * impactDetail - uboSky.moonLibration * 2.0 / PI;
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
                            float rayAngle = TAU * hash12(vec2(
                                float(impact) * 7.0 + float(ray) * 13.0,
                                float(ray) * 3.0 + float(impact) * 11.0
                            ));
                            vec3 rayDirection = impactEast * cos(rayAngle) + impactNorth * sin(rayAngle);
                            float alongRay = atan(
                                dot(moonSurfaceNormal, rayDirection),
                                dot(moonSurfaceNormal, impactNormal)
                            ) * 4.0;
                            if (alongRay <= 0.0)
                                continue;

                            float wobble = noise(vec2(alongRay * 3.0 + float(impact) * 20.0, float(ray) * 5.0)) - 0.5;
                            vec3 rayPlaneNormal = cross(impactNormal, rayDirection);
                            float perpendicularDistance = abs(
                                asin(clamp(dot(moonSurfaceNormal, rayPlaneNormal), -1.0, 1.0)) * 4.0 + wobble * 0.06
                            );
                            float rayWidth = 0.025 + noise(vec2(float(ray) * 9.0, float(impact) * 4.0)) * 0.015;
                            float rayLine = smoothstep(rayWidth, rayWidth * 0.2, perpendicularDistance);
                            float rayIntensity = 0.5 + hash12(vec2(float(ray) * 11.0, float(impact) * 6.0)) * 0.5;
                            impactRays = max(impactRays, rayLine * rayIntensity);
                        }
                        impactHighlight = max(impactHighlight, saturate(
                            (impactRays * distanceFade * 0.07 + halo + webbing) * ejectaStartFade * ejectaFade * 6
                        ));
                    }
                    surfaceNoise = mix(surfaceNoise, 1, impactHighlight * 0.52);
                }

                // The opaque disk occludes stars and nebulae while its dark side matches the night sky.
                vec3 moonDarkSide = skyColorPreStars;
                if (skyBlend > 0.001) {
                    float horizonStarFade = nightSkyHorizonFade(sky.upAmount, horizonShift);
                    moonDarkSide = blendSkyBackground(moonDarkSide, STARFIELD_BACKGROUND_COLOR, skyBlend * horizonStarFade);
                }

                float lambert = dot(moonSurfaceNormal, moonLightDir);
                float terminatorJitter = (noiseTerrain - .5) * 0.041;
                // Surface relief only perturbs incidence near the terminator.
                float terminatorRoughness =
                    (1.0 - smoothstep(0.05, 0.35, abs(lambert))) *
                    smoothstep(0.05, 0.25, moonLocalZ);
                float roughLambert = lambert + terminatorJitter * terminatorRoughness;
                float viewCos = moonLocalZ;
                // Lunar regolith scatters closer to Lommel-Seeliger than ideal Lambertian diffuse.
                float isLit = uboSky.moonIllumination < 0.001 ? 0.0 : moonDiffuse(roughLambert, viewCos, phaseCos);
                // Coverage includes the disk edge and terminator together, rather
                // than multiplying two independently blurred masks.
                isLit = mix(isLit, blurredCoverage.y / max(blurredCoverage.x, 1e-7), moonBlurBlend);

                // Earth appears full from the moon when their Earth-to-sun and Earth-to-moon
                // directions align, independently of overrides to the displayed lunar phase.
                // Use unshifted directions so the artistic horizon offset does not affect this.
                float earthPhaseCos = clamp(dot(uboSky.sunDir, uboSky.moonDir), -1.0, 1.0);
                float earthPhaseAngle = acos(earthPhaseCos);
                float earthPhaseSin = sqrt(max(0.0, 1.0 - earthPhaseCos * earthPhaseCos));
                // Disk-integrated diffuse reflection includes both illuminated area and incidence.
                float earthPhase = max(0.0, (earthPhaseSin + (PI - earthPhaseAngle) * earthPhaseCos) / PI);
                const float earthRadiusOverDistance = 6371.0 / 384400.0;
                float earthshine = 0.4 * earthRadiusOverDistance * earthRadiusOverDistance * earthPhase;
                // Earth is approximately along the viewing direction: the same regolith
                // response as full sunlight, including its mild Lambertian limb falloff.
                earthshine *= mix(1.0, viewCos, 0.15);
                earthshine *= 100; // looks about right

                // Keep surface contrast below the output's clipping threshold.
                // Ejecta raise reflectance toward the same peak.
                float surfaceContrast = smoothstep(0.65, 1.0, surfaceNoise);
                float surfaceDetail = mix(0.14, 1.0, surfaceContrast);
                // Broad maria remain recognizable while fine detail disappears.
                // Fade their contrast as the angular blur exceeds the disk size.
                if (moonBlurBlend > 0.0) {
                    vec2 surface = vec2(dot(blurredLocal, moonRight), dot(blurredLocal, moonUp));
                    float radius = length(surface);
                    if (radius > 1e-4)
                        surface *= asin(min(radius, 1.0)) / radius;
                    surface = librationRotation * (surface + uboSky.moonLibration * (2.0 / PI));
                    float blurredSea = moonFbm((surface * 4.0 + vec2(50.0)) * 0.45 + vec2(9.8, 5.6));
                    float sea = 1.0 - smoothstep(0.377, 0.477, blurredSea);
                    float detailVisibility = moonRadius * moonRadius / (moonRadius * moonRadius + dot(diskBlur, diskBlur));
                    surfaceDetail = mix(surfaceDetail, 0.3 * mix(1.0, 0.88, sea * detailVisibility), moonBlurBlend);
                }
                vec3 moonLight = uboSky.moonDiskColor * surfaceDetail * (isLit + earthshine) * diskFogTransmittance;
                vec3 background = applySkyFog(moonDarkSide, fogTransmittance);
                vec3 moonContribution = moonLight;
                if (toneMap)
                    moonContribution = toneMapSkyDisk(moonLight, background);

                // Fade after compression so HDR highlights cannot undo the daytime fade.
                moonCompositeColor = background;
                moonDiskLight = moonContribution * moonDayVisibility;

                // Fade moon near the horizon to match the star/nebula horizon fade
                float moonHorizonFade = nightSkyHorizonFade(diskUpAmount, horizonShift);
                moonCompositeAlpha = moonDisk * uboSky.moonVisibility * moonHorizonFade;
            }

            // Place a real-sized moon's glare outside the artistic disk without scaling its width or intensity.
            float rimDistanceDegrees = degrees(max(0.0,
                acos(clamp(moonDot, 0.0, 1.0)) - moonBaseRadius * uboSky.moonSizeMult));
            float glareAngleDegrees = rimDistanceDegrees + 0.25;
            vec3 toRim = moonViewDir - moonDir * moonDot;
            vec3 toLight = moonIlluminationDir - moonDir * dot(moonIlluminationDir, moonDir);
            float litSide = dot(toRim, toLight) / max(length(toRim) * length(toLight), 1e-5);
            float phaseCos = 2.0 * uboSky.moonIllumination - 1.0;
            float phaseWeight = mix(1.0, smoothstep(-0.2, 0.5, litSide), sqrt(max(0.0, 1.0 - phaseCos * phaseCos)));
            // Stiles-Holladay: Lveil / Lmoon = 10 * solidAngle / angleDegrees^2.
            // A 0.5° disk gives 0.0006; 0.3 approximates mean surface reflectance above.
            // The model is invalid near the limb: soften its core over 0.75° rather than extrapolating a bright rim.
            // https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=917534
            float halo = 0.3 * 0.0006 / (glareAngleDegrees * glareAngleDegrees + 0.75 * 0.75);
            halo *= (1.0 - sharpMoonDisk) * uboSky.moonIllumination * phaseWeight * moonDayVisibility * uboSky.moonVisibility;
            halo *= 6; // looks about right
            halo *= uboSky.moonSizeMult * uboSky.moonSizeMult;
            moonHalo = uboSky.moonDiskColor * halo * nightSkyHorizonFade(diskUpAmount, horizonShift);
        }
    }

    vec3 atmosphericForeground = shootingStarColor;

    // Auroras lose contrast against a bright sky much sooner than the moon.
    float auroraContrast = 1.0 / (1.0 + linearSrgbLuminance(skyColorPreStars) * 1200.0);
    float auroraStrength = nightFactor * uboSky.auroraVisibility * auroraContrast;
    if (auroraStrength > 0.001)
        atmosphericForeground += proceduralAurora(viewDir, elapsedTime) * auroraStrength;

    skyColor = applySkyFog(skyColor, fogTransmittance);
    skyColor += moonHalo * diskFogTransmittance;
    // Keep opaque moon coverage separate from the light it adds. This also
    // lets water shadow either disk without darkening the reflected background.
    skyColor = mix(skyColor, moonCompositeColor, moonCompositeAlpha);
    skyColor += skyFogGlow(viewDir, sky.sunDir, moonDir, fogTransmittance);
    // Shooting stars and auroras are in front of the moon, but still attenuated by fog.
    skyColor += atmosphericForeground * fogTransmittance;

    return SkySample(skyColor, sunLight * (1.0 - moonCompositeAlpha), moonDiskLight * moonCompositeAlpha);
}

vec3 sampleSky(vec3 viewDir, bool correctProjection, bool toneMap) {
    SkySample sky = sampleSky(viewDir, viewDir, correctProjection, toneMap, vec3(0, -1, 0), 0.0);
    return sky.background + sky.sun + sky.moon;
}
