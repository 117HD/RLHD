/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
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
#include <uniforms/global.glsl>
#include <uniforms/water_types.glsl>

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>
#include <utils/noise.glsl>
#include <utils/lights.glsl>
#include <utils/misc.glsl>
#include <utils/water_reflection.glsl>
#include <utils/shadows.glsl>
#include <utils/fresnel.glsl>

#if LEGACY_WATER
    #include <utils/legacy_water.glsl>
#else

vec3 sampleWaterSurfaceNormal(int waterTypeIndex, vec3 position) {
    WaterType waterType = getWaterType(waterTypeIndex);
    vec2 worldUv = -position.xz / 128;

    float waveHeight = 1;
    float waveSpeed = .0072;
    switch (waterTypeIndex) {
        case WATER_TYPE_BLACK_TAR_FLAT:
            waveHeight = .1;
            waveSpeed *= .42;
            break;
        case WATER_TYPE_MUDDY_WATER:
            waveHeight = .1;
            break;
        case WATER_TYPE_BLOOD:
            waveHeight = .75;
            break;
        case WATER_TYPE_ICE:
        case WATER_TYPE_ICE_FLAT:
            waveHeight = .3;
            waveSpeed = 0;
            break;
        case WATER_TYPE_ABYSS_BILE:
            waveHeight = .7;
            break;
    }

    vec2 uv1 = worldUv / 26 + waveSpeed * elapsedTime * vec2( 1, -4);
    vec2 uv2 = worldUv /  6 + waveSpeed * elapsedTime * vec2(-2,  1);

    vec3 n1 = texture(waterNormalMaps, vec3(uv1, 0)).xyz;
    vec3 n2 = texture(waterNormalMaps, vec3(uv2, 1)).xyz;

    // Scale wave strength

    n1.z /= waveHeight * .225;
    n2.z /= waveHeight * .8;

    // Normalize
    n1.xy = n1.xy * 2 - 1;
    n2.xy = n2.xy * 2 - 1;
    // Tangent space to world, assuming flat surface
    n1.z *= -1;
    n2.z *= -1;
    n1 = normalize(n1.xzy);
    n2 = normalize(n2.xzy);

    return normalize(n1 + n2);
}

void sampleUnderwater(inout vec3 outputColor, int waterTypeIndex, float depth) {
    WaterType waterType = getWaterType(waterTypeIndex);

    // Ignore refraction for the underwater position, since it would require computing a quartic equation
    vec3 fragPos = IN.position;
    vec3 underwaterNormal = normalize(IN.normal);
    vec3 surfaceNormal = vec3(0, -1, 0); // assume a flat surface

    vec3 sunDir = -lightDir; // the light's direction from the sun towards any fragment
    vec3 refractedSunDir = refract(sunDir, surfaceNormal, IOR_AIR_TO_WATER);
    float sunToFragDist = depth / refractedSunDir.y;

    vec3 camToFrag = normalize(fragPos - cameraPos);
    float fragToSurfaceDist = abs(depth / camToFrag.y);

    // Incoming and outgoing light directions pointing away from the fragment
    vec3 omega_i = -refractedSunDir;
    vec3 omega_o = -camToFrag;

    // TODO: this may produce a distorted surface pos on slopes
    vec3 surfacePos = fragPos - camToFrag * fragToSurfaceDist;
    surfaceNormal = sampleWaterSurfaceNormal(waterTypeIndex, surfacePos);
    omega_o = -refract(camToFrag, surfaceNormal, IOR_AIR_TO_WATER);

    // Initialize light intensities with linear RGB colors
    vec3 directionalLight = lightColor * lightStrength;
    vec3 ambientLight = ambientColor * ambientStrength;
    vec3 seabedAlbedo = outputColor;

    // Naming convention taken from http://graphics.cs.cmu.edu/courses/15-468/2021_spring/lectures/lecture17.pdf

    // Absorption, scattering and diffuse attenuation coefficients of pure sea water
    // From page 31 of https://misclab.umeoce.maine.edu/boss/classes/RT_Weizmann/Chapter3.pdf
    vec3 sigma_a_pureWater = vec3(
        .244,  // ~red   600 nm
        .0638, // ~green 550 nm
        .0145  // ~blue  450 nm
    );
    vec3 sigma_s_pureWater = vec3(
        .0014, // ~red   600 nm
        .0019, // ~green 550 nm
        .0045  // ~blue  450 nm
    );

    // Initialize absorption and scattering coefficients based on water type
    vec3 sigma_a_particles = vec3(0);
    vec3 sigma_s_particles = vec3(0);
    // Scattering anisotropy factor (average cosine), used in the Henyey-Greenstein phase function
    // Taken from https://www.researchgate.net/figure/a-Correlation-of-Mie-scattering-coefficient-and-wavelength-and-b-anisotropy-factor_fig5_337670010
    float g = .924;

    switch (waterTypeIndex) {
        default:
        case WATER_TYPE_WATER:
        case WATER_TYPE_PLAIN_WATER:
            // Coefficients for Jerlov water types, taken from https://doi.org/10.1364/AO.54.005392
            // https://www.researchgate.net/figure/Left-Jerlov-water-types-based-on-the-attenuation-coefficients-bl-Types-I-III-are_fig1_338015606

            // Jerlov I
            sigma_a_particles = vec3(.228, .062, .018);
            sigma_s_particles = vec3(1.22E-03, 1.70E-03, 3.81E-03);
            g = .88;
            break;
        case WATER_TYPE_BLOOD:
            sigma_a_particles = (1 - vec3(.9, .1, .2)) * 7;
            sigma_s_particles = vec3(1, .1, .1) * .05;
            g = .2;
            break;
        case WATER_TYPE_SCAR_SLUDGE:
            sigma_a_particles = vec3(.309, .3, .1548) * .35;
            sigma_a_particles += vec3(0.005, 0.0175, 0.0275) * 20;
            break;
        case WATER_TYPE_CYAN_WATER:
            sigma_a_particles = sigma_a_pureWater * 4;
            sigma_s_particles = vec3(.325, .659, .675);
            g = .01;
            break;
        case WATER_TYPE_GREEN_CAVE_WATER:
            sigma_a_particles = (1.1 - vec3(0, .973, .718)) * .2;
            sigma_s_particles = vec3(.01, .973, .418) * .01;
            break;
        case WATER_TYPE_SWAMP_WATER:
        case WATER_TYPE_POISON_WASTE:
        case WATER_TYPE_ABYSS_BILE:
        case WATER_TYPE_DARK_BLUE_WATER:
            // Jerlov 1C
            sigma_a_particles = vec3(.236, .076, .105);
            sigma_s_particles = vec3(.314, .365, .514);
            g = .89;
            break;
    }

    // Kind of hacky way to fix the edges for some water types
    switch (waterTypeIndex) {
        case WATER_TYPE_BLOOD:
        case WATER_TYPE_SWAMP_WATER:
        case WATER_TYPE_POISON_WASTE:
        case WATER_TYPE_MUDDY_WATER:
        case WATER_TYPE_SCAR_SLUDGE:
        case WATER_TYPE_CYAN_WATER:
        case WATER_TYPE_ARAXXOR_WASTE:
            depth += 48;
            sunToFragDist = depth / refractedSunDir.y;
            fragToSurfaceDist = abs(depth / camToFrag.y);
            break;
    }

    // Convert coefficients from meters to in-game units
    sigma_a_particles /= 128;
    sigma_s_particles /= 128;
    sigma_a_pureWater /= 128;
    sigma_s_pureWater /= 128;

    vec3 sigma_a = sigma_a_pureWater + sigma_a_particles;
    vec3 sigma_s = sigma_s_pureWater + sigma_s_particles;
    // extinction coefficient = absorption + scattering
    vec3 sigma_t = sigma_a + sigma_s;

    // Compute single-scattering of directional light
    float cosTheta = dot(-omega_i, omega_o);

    // P = normalized phase function
    // B = backscatter fraction (portion scattered backwards)

    // Scattering phase function of pure water
    // https://www.oceanopticsbook.info/view/inherent-and-apparent-optical-properties/visualizing-vsfs
    float P_pureWater = 0.06225 + 0.05197875 * cosTheta*cosTheta;
    float B_pureWater = 0.5; // symmetrical

    // Henyey-Greenstein phase function
    // https://www.oceanopticsbook.info/view/scattering/level-2/the-henyey-greenstein-phase-function
    float P_hg = (1 - g*g) / (4 * PI * pow(1 + g*g - 2*g*cosTheta, 3.f / 2.f));
    float B_hg = (1 - g) / (2 * g) * ((1 + g) / sqrt(1 + g*g) - 1);

    // We start off by calculating the amount of light reaching the seabed fragment
    vec3 L_directional = directionalLight;

    // Account for loss to Fresnel reflection upon entering the water body
    L_directional *= 1 - calculateFresnel(max(0, dot(-sunDir, surfaceNormal)), IOR_WATER);

    // Add underwater caustics as additional directional light
    if (shorelineCaustics) {
        vec2 causticsUv = worldUvs(3.333);
        const vec2 direction = vec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(13) * direction;
        vec2 flow2 = causticsUv * 1.5 + animationFrame(17) * -direction;
        vec3 caustics = sampleCaustics(flow1, flow2, depth * .00003);

        // Apply caustics color based on the environment
        // Usually this falls back to directional lighting
        caustics *= underwaterCausticsColor;

        float average = texture(textureArray, vec3(0, 0, MAT_CAUSTICS_MAP.colorMap), 100).r;
        caustics -= average;
        caustics *= 2;

        // Fade caustics out too close to the shoreline
        caustics *= min(1, smoothstep(0, 1, depth / 32));

        // Fade caustics out with depth, since they should decay sharply due to focus
        caustics *= max(0, 1 - smoothstep(0, 1, depth / 768));

        // Add caustics as additional directional light
        L_directional *= 1 + caustics;
    }

    // Account for shadowing of the directional light
    if (waterTransparency && !waterType.isFlat /* Disable shadows for flat water, as it needs more work */) {
        // For shadows, we can take refraction into account, since sunlight is parallel
        vec3 surfaceSunPos = fragPos - refractedSunDir * sunToFragDist;
        surfaceSunPos += refractedSunDir * 32; // Push the position a short distance below the surface
        vec2 distortion = vec2(0);
        {
            vec2 flowMapUv = worldUvs(26) + animationFrame(26 * waterType.duration);
            float flowMapStrength = 0.025;
            vec2 uvFlow = texture(textureArray, vec3(flowMapUv, waterType.flowMap)).xy;
            distortion = uvFlow * .001 * (1 - exp(-.01 * depth));
        }
        // TODO: This would be nicer if blurred based on optical depth
        float shadow = sampleShadowMap(surfaceSunPos, distortion, dot(-sunDir, underwaterNormal));
        // Apply shadow to directional light
        L_directional *= 1 - shadow;
    }

    // Attenuate the directional light as it travels down to the seabed
    L_directional *= exp(-sigma_t * sunToFragDist);

    // Calculate Lambertian reflection from the seabed
    L_directional *= max(0, dot(omega_i, underwaterNormal));

    // Also calculate the amount of ambient lighting reaching the fragment
    vec3 L_ambient = ambientLight;

    // Crudely account for average loss to Fresnel reflection upon entering the water body
    L_ambient *= .975;

    // Rough approximation of diffuse attenuation coefficient
    vec3 K_d = sigma_a + sigma_s_pureWater * B_pureWater + sigma_s_particles * B_hg;
    L_ambient *= exp(-K_d * depth);

    // Rough approximation of Lambertian reflection from the seabed
    L_ambient *= max(0, dot(vec3(0, -1, 0), underwaterNormal));

    // Now we're ready to start assembling the outgoing light L

    // Calculate Lambertian reflection from the seabed
    vec3 L = seabedAlbedo * (L_directional + L_ambient);

    // Attenuate the reflected light as it travels back up towards the surface
    L *= exp(-sigma_t * fragToSurfaceDist);

    // QSSA for upwelling radiance at the surface for a given depth
    // https://www.oceanopticsbook.info/view/radiative-transfer-theory/level-2/the-quasi-single-scattering-approximation
    float mu_sw = refractedSunDir.y; // cos(theta) between downward direction in water and the sunlight's direction
    float mu = omega_o.y;
    vec3 E_d0 = (directionalLight * .5 + ambientLight) * mu_sw; // downwelling plane irradiance at the surface
    vec3 b_pureWater = sigma_s_pureWater * B_pureWater; // backscatter coefficient
    vec3 zeta_star_pureWater = (sigma_a + b_pureWater) * depth; // optical depth
    vec3 b_particles = sigma_s_particles * B_hg; // backscatter coefficient
    vec3 zeta_star_particles = (sigma_a + b_particles) * depth; // optical depth

    // Add scattering contribution from pure water and particles
    vec3 QSSA = E_d0 / (mu_sw - mu) * (
        b_pureWater / (sigma_a + b_pureWater)
            * P_pureWater / B_pureWater
            * (1 - exp(zeta_star_pureWater * (1 / mu - 1 / mu_sw)))
        + b_particles / (sigma_a + b_particles)
            * P_hg / B_hg
            * (1 - exp(zeta_star_particles * (1 / mu - 1 / mu_sw)))
    );
    L += QSSA;

    // Fresnel reflection upon leaving the water body is already accounted for by the water surface fragment
    outputColor = L;

    // Break up color banding with some noise
    outputColor.rgb += (gradientNoise(gl_FragCoord.xy) - .5) / 0xFF;
}

vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);

    float slope = abs(IN.flatNormal.y);
    if (slope < .8) {
        float waterfallMask = smoothstep(.8, .6, slope);

        vec3 bgColor = srgbToLinear(vec3(.063, .119, .194));
        vec3 bgColor2 = srgbToLinear(vec3(.063, .2, .3));
        vec3 fgColor = srgbToLinear(vec3(.9));

        vec3 N = IN.flatNormal;
        const float discretize = 5;
        N = floor(N * discretize) / discretize;
        vec3 T = normalize(vec3(-N.z, 0, N.x)); // up cross n
        vec3 B = cross(N, T);
        mat3 TBN = mat3(T, B, N);
        mat3 invTBN = transpose(TBN);

        const float uvScale = .15;
        vec3 uvw = (invTBN * IN.position) / -128;
        vec2 uv = uvw.xy;
        uv *= uvScale;

//        uv.y -= elapsedTime * uvw.z * .0001;

        vec2 flowMapUv = vec2(uv.x, IN.position.y / 128 * uvScale) * .3 - animationFrame(vec2(200, 4));
        vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
        uv += uvFlow * .2;

        uv.y -= elapsedTime * .5;

        vec3 n = texture(waterNormalMaps, vec3(uv, 0)).xyz;
        n.xy = n.xy * 2 - 1;
        n.z *= .3;
        n = TBN * n;
        n = normalize(n);

        float cosAngle = max(0, dot(n, viewDir));
        float fresnel = calculateFresnel(cosAngle, IOR_WATER);

        vec4 dst = vec4(0);

        vec4 src = vec4(0);

        vec3 light = lightColor * lightStrength + ambientColor * ambientStrength;

        src.rgb = mix(bgColor, bgColor2, cosAngle) * light;
//        src += srgbToLinear(fogColor) * fresnel;
        vec3 omega_h = normalize(viewDir + lightDir); // half-way vector
        vec3 sunSpecular = pow(max(0, dot(n, omega_h)), 500) * lightColor * lightStrength;
        src.rgb += sunSpecular;
        src.a = waterfallMask * .15;
        dst = dst * (1 - src.a) + src;

//        src = mix(bgColor, bgColor2, pow(max(0, dot(N, viewDir)), 7));
//        float noise = 1;
//        noise *= (bccNoiseClassic(vec3(uv * 30 + vec2(0, elapsedTime), 0)) + 1) / 2;
//        noise *= (bccNoiseClassic(vec3(uv * 13 + vec2(0, elapsedTime * 1.5), 0)) + 1) / 2;
//        src = vec4(light, noise * .025);
//
//        // Blend as overlay
//        dst.rgb = src.rgb * src.a + dst.rgb * dst.a * (1 - src.a);
//        dst.a = src.a + dst.a * (1 - src.a);
//        dst.rgb /= dst.a;

        return dst;
    }

    float specularGloss = 500; // Ignore values set per water type, as they don't make a lot of sense
    float specularStrength = 1;

    vec3 ambientLight = ambientColor * ambientStrength;
    vec3 directionalLight = lightColor * lightStrength;

    vec3 N = sampleWaterSurfaceNormal(waterTypeIndex, IN.position);

    vec3 fragToCam = viewDir;
    vec3 I = -viewDir; // incident

    // Assume the water is level
    vec3 flatR = reflect(I, vec3(0, -1, 0));
    vec3 R = reflect(I, N);
    float distortionFactor = 50;
    float reflectionBias = 0;

    switch (waterTypeIndex) {
        case WATER_TYPE_ICE:
            distortionFactor *= 12;
            break;
        case WATER_TYPE_ABYSS_BILE:
            distortionFactor *= 4;
            break;
    }

    vec4 reflection = vec4(
        sampleWaterReflection(flatR, R, distortionFactor),
        calculateFresnel(dot(fragToCam, N), IOR_WATER)
    );

    switch (waterTypeIndex) {
        case WATER_TYPE_BLOOD:
            reflection.r = max(reflection.r, .4f);
            break;
    }

    // Break up color banding with some noise
    reflection.a += (gradientNoise(gl_FragCoord.xy) - .5) / 0xFF;

    vec3 additionalLight = vec3(0);

    vec3 omega_i = lightDir; // incoming = frag to sun
    vec3 omega_o = viewDir; // outgoing = frag to camera
    vec3 omega_h = normalize(omega_o + omega_i); // half-way vector
    vec3 omega_n = N; // surface normal

    #define WATER_SPECULAR_MODE 1
    #if WATER_SPECULAR_MODE == 1 || WATER_SPECULAR_MODE == 3 // sun or sun & lights
        // TODO: this doesn't work well for water
        vec3 sunSpecular = pow(max(0, dot(N, omega_h)), specularGloss) * directionalLight * specularStrength;
        additionalLight += sunSpecular;
    #endif

    // Begin constructing final output color
    vec4 dst = reflection;

    // In theory, we could just add the light and be done with it, but since the color
    // will be multiplied by alpha during alpha blending, we need to divide by alpha to
    // end up with our target amount of additional light after alpha blending
    dst.rgb += additionalLight / dst.a;

    // The issue now is that or color may exceed 100% brightness, and get clipped.
    // To work around this, we can adjust the alpha component to let more of the light through,
    // and adjust our color accordingly. This necessarily causes the surface to become more opaque,
    // but since we're adding lots of light, this should have minimal impact on the final picture.
    float maxIntensity = max(max(dst.r, dst.g), dst.b);
    // Check if the color would get clipped
    if (maxIntensity > 1) {
        // Bring the brightest color back down to 1
        dst.rgb /= maxIntensity;
        // And bump up the alpha to increase brightness instead
        dst.a *= maxIntensity;
        // While not strictly necessary, we might as well clamp the alpha component in case it exceeds 1
        dst.a = min(1, dst.a);
    }

    // TODO: specify transparent, faked depth or lambertian per water type
    // A highly scattering medium roughly approaches a Lambertian reflector
    bool lambertian =
        waterTypeIndex == WATER_TYPE_MUDDY_WATER ||
        waterTypeIndex == WATER_TYPE_ARAXXOR_WASTE ||
        waterTypeIndex == WATER_TYPE_BLACK_TAR_FLAT;

    if (lambertian) {
        // TODO: make waterType.surfaceColor consistently linear RGB in the UBO
        vec3 surfaceColor = srgbToLinear(waterType.surfaceColor);

        switch (waterTypeIndex) {
            case WATER_TYPE_MUDDY_WATER:
                surfaceColor = vec3(.238, .161, .007) * .065;
                break;
            case WATER_TYPE_ARAXXOR_WASTE:
                surfaceColor = vec3(22, 255, 13) / 0xFF * .5;
                break;
        }

        vec4 src = dst;
        dst.rgb = surfaceColor * (ambientLight + directionalLight * max(0, dot(N, omega_o)));
        dst.rgb = mix(dst.rgb, src.rgb, src.a);
        dst.a = 1;
    } else if (waterType.isFlat || !waterTransparency) { // If the water is opaque, blend in a fake underwater surface
        // Computed from packedHslToSrgb(6676)
        const vec3 underwaterColor = vec3(0.04856183, 0.025971446, 0.005794384);
        int depth = 768; // works for boat cutscenes such as when going diving with Murphy
//        int depth = 512; // works alright for the obelisk fix in Catherby

        // TODO: add a way for tile overrides to specify water depth
        if (waterTypeIndex == WATER_TYPE_ABYSS_BILE)
            depth = 96;

        vec4 src = dst;
        dst.rgb = underwaterColor;
        sampleUnderwater(dst.rgb, waterTypeIndex, depth);

        dst.rgb = mix(dst.rgb, src.rgb, src.a);
        dst.a = 1;
    }

    #if WATER_FOAM
    if (waterType.hasFoam == 1) {
        vec2 flowMapUv = worldUvs(5) + animationFrame(30 * waterType.duration);
        float flowMapStrength = .25;
        vec2 uvFlow = texture(textureArray, vec3(flowMapUv, waterType.flowMap)).xy;
        vec2 uv = IN.uv + uvFlow * flowMapStrength;
        float foamMask = texture(textureArray, vec3(uv, waterType.foamMap)).r;
        foamMask *= .075;
        float shoreLineMask = 1 - dot(IN.texBlend, vHsl / 127.f);
        shoreLineMask *= shoreLineMask;
        shoreLineMask *= shoreLineMask;
        shoreLineMask *= shoreLineMask;

        vec3 light = ambientColor * ambientStrength + lightColor * lightStrength;
        vec4 foam = vec4(light, shoreLineMask * foamMask);

        // Blend in foam at the very end as an overlay
        dst.rgb = foam.rgb * foam.a + dst.rgb * dst.a * (1 - foam.a);
        dst.a = foam.a + dst.a * (1 - foam.a);
        dst.rgb /= dst.a;
    }
    #endif

    return dst;
}

#endif
