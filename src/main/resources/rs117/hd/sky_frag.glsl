#version 330

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/constants.glsl>
#include <utils/output_transform.glsl>
#include <utils/misc.glsl>
#include <utils/starfield.glsl>
#include <utils/aurora.glsl>
#include <utils/sky.glsl>
#include <utils/sky_fog.glsl>
#include <utils/hash.glsl>

in vec2 fScreenPos;

out vec4 FragColor;

float moonFbm(in vec2 st) {
    float value = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 6; i++) {
        value += amplitude * noise(st);
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
    float horizonShift = nightHorizonOffset(uboSky.starHorizonHeight);

    // Stars appear first opposite the sun, then spread across the low-sun sky.
    float baseProgress = 1.0 - sky.nightFade;
    float sunProximity = sky.sunSideBlend * (1.0 - sky.zenithBlend);
    // Aurora visibility is independent of the night-sky background.
    float nightFactor = pow(baseProgress, mix(0.4, 0.9, sunProximity));
    float skyBlend = nightFactor;
    #if STAR_MODE != STAR_MODE_OFF
        float starBlend = nightFactor * uboSky.starVisibility;
    #endif
    vec3 shootingStarColor = vec3(0.0);
    if (skyBlend > 0.001) {
        // Individual stars are drawn separately as point sprites.
        vec3 nightSkyColor = nightSkyBackground(viewDir, elapsedTime);

        // Converge to the fog-matched gradient at the horizon.
        float horizonStarFade = nightSkyHorizonFade(sky.upAmount, horizonShift);
        skyColor = blendSkyBackground(skyColor, nightSkyColor, skyBlend * horizonStarFade);
    }
    // Shooting stars are atmospheric and render in front of the moon.
    #if STAR_MODE != STAR_MODE_OFF
        if (starBlend > 0.001 && -viewDir.y > 0.05 + horizonShift)
            shootingStarColor = shootingStars(viewDir, elapsedTime) * starBlend;
    #endif

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
    skyColor += uboSky.sunColor * sunDisk * sunHorizon * mix(0.6, 1.0, sunMu);

    // Render the moon disk
    if (uboSky.moonVisibility > 0.001) {
        // Apply the sun's perceived-horizon offset.
        vec3 moonDir = normalize(vec3(uboSky.moonDir.x, -uboSky.moonDir.y + HORIZON_OFFSET, uboSky.moonDir.z));
        vec3 moonIlluminationDir = normalize(vec3(uboSky.moonIlluminationDirection.x, -uboSky.moonIlluminationDirection.y + HORIZON_OFFSET, uboSky.moonIlluminationDirection.z));

        float moonDot = dot(viewDir, moonDir);

        // Daylight lowers lunar contrast rather than making the disk transparent.
        // Scale against the local sky brightness so the moon remains subtly visible
        // in daytime, but naturally becomes prominent as the sky darkens.
        float skyLuminance = linearSrgbLuminance(skyColorPreStars);
        float moonDayVisibility = 1.0 / (1.0 + skyLuminance * 10.0);

        // Fade the moon near the sun.
        float sunMoonDot = dot(moonDir, sky.sunDir);
        float sunProximityFade = smoothstep(0.9, 0.7, sunMoonDot);
        moonDayVisibility *= sunProximityFade;

        if (moonDot > 0.0 && moonDayVisibility > 0.001) {
            // Deliberately enlarged ~1.9° moon radius, scaled per environment.
            float moonBaseRadius = acos(0.99945);
            float moonAngularRadius = cos(moonBaseRadius * uboSky.moonSizeMult);
            float edgeWidth = moonDot > 0.01 ? max(fwidth(moonDot) * 1.5, 1e-7) : 0;

            // Keep the antialiased edge within the sphere used for surface shading.
            float moonDisk = smoothstep(moonAngularRadius, moonAngularRadius + edgeWidth, moonDot);
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

                // Large-scale terrain - broad tonal variation
                float largeTerrain = moonFbm(moonUV * 0.5);

                // Medium-scale detail
                float medTerrain = moonFbm(moonUV * 1.5);

                // Fine surface texture
                float fineTerrain = moonFbm(moonUV * 3);

                float noiseTerrain = moonFbm(moonUV * 51);

                // Base brightness from blended terrain layers
                vec4 portion = vec4(0.48, 0.27, 0.17, 0.07);
                float surfaceNoise = dot(vec4(largeTerrain, medTerrain, fineTerrain, noiseTerrain), portion);
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

                // The opaque disk occludes stars and nebulas while its dark side matches the night sky.
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
                float lightCos = max(roughLambert, 0.0);
                float viewCos = moonLocalZ;
                // Lunar regolith scatters closer to Lommel-Seeliger than ideal Lambertian diffuse.
                float lommelSeeliger = 2.0 * lightCos / max(lightCos + viewCos, 1e-4);
                float lunarLambertWeight = mix(0.6, 0.15, max(phaseCos, 0.0));
                float lunarDiffuse = mix(lommelSeeliger, lightCos, lunarLambertWeight);
                float terminatorFade = smoothstep(-0.14, 0.08, roughLambert);
                float isLit = uboSky.moonIllumination < 0.001 ? 0.0 : clamp(lunarDiffuse, 0.0, 1.0) * terminatorFade;
                float terminatorProximity = 1.0 - smoothstep(0.02, 0.2, abs(roughLambert));
                float crescentEdgeFade = smoothstep(0.0, 0.25, moonLocalZ);
                isLit *= mix(1.0, crescentEdgeFade, terminatorProximity);

                // Keep surface contrast below the output's clipping threshold.
                // Ejecta raise reflectance toward the same peak.
                float surfaceContrast = smoothstep(0.65, 1.0, surfaceNoise);
                float surfaceDetail = mix(0.14, 1.0, surfaceContrast);
                vec3 moonBrightSide = moonDarkSide + uboSky.moonDiskColor * surfaceDetail;

                // Keep the disk opaque so stars and the sky gradient cannot show through crescents.
                vec3 moonColor = mix(moonDarkSide, moonBrightSide, isLit * moonDayVisibility);

                // Fade moon near the horizon to match the star/nebula horizon fade
                float moonHorizonFade = nightSkyHorizonFade(sky.upAmount, horizonShift);
                float moonAlpha = moonDisk * uboSky.moonVisibility * moonHorizonFade;

                skyColor = mix(skyColor, moonColor, moonAlpha);
            }

            // Place a real-sized moon's glare outside the artistic disk without scaling its width or intensity.
            float rimDistanceDegrees = degrees(max(0.0,
                acos(clamp(moonDot, 0.0, 1.0)) - moonBaseRadius * uboSky.moonSizeMult));
            float glareAngleDegrees = rimDistanceDegrees + 0.25;
            vec3 toRim = viewDir - moonDir * moonDot;
            vec3 toLight = moonIlluminationDir - moonDir * dot(moonIlluminationDir, moonDir);
            float litSide = dot(toRim, toLight) / max(length(toRim) * length(toLight), 1e-5);
            float phaseCos = 2.0 * uboSky.moonIllumination - 1.0;
            float phaseWeight = mix(1.0, smoothstep(-0.2, 0.5, litSide), sqrt(max(0.0, 1.0 - phaseCos * phaseCos)));
            // Stiles-Holladay: Lveil / Lmoon = 10 * solidAngle / angleDegrees^2.
            // A 0.5° disk gives 0.0006; 0.3 approximates mean surface reflectance above.
            // The model is invalid near the limb: soften its core over 0.75° rather than extrapolating a bright rim.
            // https://tsapps.nist.gov/publication/get_pdf.cfm?pub_id=917534
            float halo = 0.3 * 0.0006 / (glareAngleDegrees * glareAngleDegrees + 0.75 * 0.75);
            halo *= (1.0 - moonDisk) * uboSky.moonIllumination * phaseWeight * moonDayVisibility * uboSky.moonVisibility;
            skyColor += uboSky.moonDiskColor * halo * nightSkyHorizonFade(sky.upAmount, horizonShift);
        }
    }

    skyColor += shootingStarColor;

    // Auroras lose contrast against a bright sky much sooner than the moon.
    float auroraContrast = 1.0 / (1.0 + linearSrgbLuminance(skyColorPreStars) * 1200.0);
    float auroraStrength = nightFactor * uboSky.auroraVisibility * auroraContrast;
    if (auroraStrength > 0.001)
        skyColor += proceduralAurora(viewDir, elapsedTime) * auroraStrength;

    skyColor = applySkyFog(skyColor, sky.upAmount);
    skyColor = applyColorAdjustments(linearToSrgb(skyColor));
    skyColor = applyOutputCorrection(skyColor);

    // Reduce color banding
    skyColor.rgb += (hash12(gl_FragCoord.xy + elapsedTime) - 0.5) / 255.0;

    FragColor = vec4(skyColor, 1.0);
}
