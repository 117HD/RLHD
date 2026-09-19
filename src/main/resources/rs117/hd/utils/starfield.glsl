#pragma once

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/constants.glsl>
#include <utils/hash.glsl>

#define STARFIELD_BACKGROUND_COLOR vec3(0.000235294, 0.000235294, 0.000403251)

// Artistic rotation uses elapsed seconds, independently of the daylight-cycle clock.
vec3 rotateStarfield(vec3 direction, float elapsedSeconds, float artisticRotationSpeed) {
    #if STAR_MODE == STAR_MODE_REALISTIC
        float cosRotation = cos(uboSky.celestialRotation);
        float sinRotation = sin(uboSky.celestialRotation);
        return
            direction * cosRotation +
            cross(uboSky.celestialPole, direction) * sinRotation +
            uboSky.celestialPole * dot(uboSky.celestialPole, direction) * (1.0 - cosRotation);
    #elif STAR_MODE == STAR_MODE_ARTISTIC
        float rotY = elapsedSeconds * (TAU / 3600.0) * artisticRotationSpeed;
        float rotX = elapsedSeconds * (TAU / 10800.0) * artisticRotationSpeed;
        float cosY = cos(rotY);
        float sinY = sin(rotY);
        float cosX = cos(rotX);
        float sinX = sin(rotX);
        direction = vec3(cosY * direction.x + sinY * direction.z, direction.y, -sinY * direction.x + cosY * direction.z);
        return vec3(direction.x, cosX * direction.y - sinX * direction.z, sinX * direction.y + cosX * direction.z);
    #else
        return direction;
    #endif
}

vec3 inverseRotateStarfield(vec3 direction, float elapsedSeconds, float artisticRotationSpeed) {
    #if STAR_MODE == STAR_MODE_REALISTIC
        float cosRotation = cos(uboSky.celestialRotation);
        float sinRotation = -sin(uboSky.celestialRotation);
        return
            direction * cosRotation +
            cross(uboSky.celestialPole, direction) * sinRotation +
            uboSky.celestialPole * dot(uboSky.celestialPole, direction) * (1.0 - cosRotation);
    #elif STAR_MODE == STAR_MODE_ARTISTIC
        float rotY = -elapsedSeconds * (TAU / 3600.0) * artisticRotationSpeed;
        float rotX = -elapsedSeconds * (TAU / 10800.0) * artisticRotationSpeed;
        float cosY = cos(rotY);
        float sinY = sin(rotY);
        float cosX = cos(rotX);
        float sinX = sin(rotX);
        direction = vec3(direction.x, cosX * direction.y - sinX * direction.z, sinX * direction.y + cosX * direction.z);
        return vec3(cosY * direction.x + sinY * direction.z, direction.y, -sinY * direction.x + cosY * direction.z);
    #else
        return direction;
    #endif
}

// Vertical shift applied to every night-sky horizon fade band (stars, nebula, moon,
// shooting stars), in upAmount units (upAmount = -viewDir.y, so -1 is straight down
// and +1 straight up). A height of 1 is the default; 0 leaves the night sky unmasked
// and 2 masks it completely.
float nightHorizonOffset(float height) {
    return (height - 1.0) * 1.2;
}

float nebulaClusterInfluence(vec3 dir) {
    float influence = 0.0;
    for (int i = 0; i < NEBULA_CLUSTER_COUNT; i++) {
        vec3 c = uboSky.nebulaClusters[i].xyz;
        float sigma = uboSky.nebulaClusters[i].w * 6.0;
        float angleSq = max(0.0, (1.0 - dot(dir, c)) * 2.0);
        influence = max(influence, exp(-angleSq / (2.0 * sigma * sigma)));
    }
    return influence;
}

float sf_noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    // Quintic interpolant (6t^5 - 15t^4 + 10t^3). Unlike the cubic smoothstep,
    // its 1st AND 2nd derivatives vanish at cell boundaries, so adjacent cells
    // join without the visible faceting/creasing that makes value noise look blocky.
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    return mix(
        mix(
            mix(hash13(i + vec3(0,0,0)), hash13(i + vec3(1,0,0)), f.x),
            mix(hash13(i + vec3(0,1,0)), hash13(i + vec3(1,1,0)), f.x),
            f.y
        ),
        mix(
            mix(hash13(i + vec3(0,0,1)), hash13(i + vec3(1,0,1)), f.x),
            mix(hash13(i + vec3(0,1,1)), hash13(i + vec3(1,1,1)), f.x),
            f.y
        ),
        f.z
    );
}

// Fractal Brownian Motion - sums octaves of noise at increasing frequency and
// decreasing amplitude to build organic, multi-scale structure. Output is
// normalized to roughly [0,1].
float sf_fbm(vec3 p, int octaves) {
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (int o = 0; o < octaves; o++) {
        sum += amp * sf_noise(p);
        norm += amp;
        p *= 2.02;   // slightly off 2.0 to avoid octaves aligning on the lattice
        amp *= 0.5;
    }
    return sum / norm;
}

// Procedural shooting stars - returns additive color contribution
// Uses time-slotted deterministic spawning for rare, brief meteor streaks
vec3 shootingStars(vec3 viewDir, float time) {
    vec3 color = vec3(0.0);

    const float SLOT_DURATION = 15.0;

    for (int channel = 0; channel < 3; channel++) {
        float channelOffset = float(channel) * 5.0;
        float t = time - channelOffset;
        float slot = floor(t / SLOT_DURATION);
        float phase = fract(t / SLOT_DURATION);

        vec3 seed = vec3(slot, float(channel) * 137.0 + 42.0, 7.0);

        // ~12% spawn chance per slot -> ~1 meteor per 42s average
        if (hash13(seed) > 0.12) continue;

        // Start position on upper sky sphere
        float theta = hash13(seed + vec3(1.0, 0.0, 0.0)) * TAU;
        float cosElev = 1.0 - hash13(seed + vec3(2.0, 0.0, 0.0)) * 0.65;
        float sinElev = sqrt(1.0 - cosElev * cosElev);
        vec3 startPos = normalize(vec3(sinElev * cos(theta), -cosElev, sinElev * sin(theta)));

        // Travel direction (generally downward with randomization)
        float tTheta = hash13(seed + vec3(3.0, 0.0, 0.0)) * TAU;
        float tPhi = 0.3 + hash13(seed + vec3(4.0, 0.0, 0.0)) * 0.5;
        vec3 travelDir = normalize(vec3(
            sin(tPhi) * cos(tTheta),
            cos(tPhi),
            sin(tPhi) * sin(tTheta)
        ));

        float speed = 0.08 + hash13(seed + vec3(5.0, 0.0, 0.0)) * 0.06;
        float lifetime = 0.8 + hash13(seed + vec3(6.0, 0.0, 0.0)) * 0.7;
        float maxBright = 0.6 + hash13(seed + vec3(7.0, 0.0, 0.0)) * 0.6;

        // Timing within the slot
        float startDelay = 0.1 * SLOT_DURATION;
        float age = phase * SLOT_DURATION - startDelay;
        if (age < 0.0 || age > lifetime) continue;

        // Fade envelope
        float fadeIn = smoothstep(0.0, 0.15, age);
        float fadeOut = smoothstep(0.0, 0.3, lifetime - age);
        float alpha = fadeIn * fadeOut;

        // Head and tail positions
        float headDist = age * speed;
        float trailLen = speed * 0.7 * alpha;
        vec3 headPos = normalize(startPos + travelDir * headDist);
        vec3 tailPos = normalize(startPos + travelDir * max(0.0, headDist - trailLen));

        // Distance from viewDir to the meteor line segment
        vec3 seg = headPos - tailPos;
        float segLen = length(seg);
        if (segLen < 0.0001) continue;
        vec3 segN = seg / segLen;

        float tProj = dot(viewDir - tailPos, segN);
        tProj = clamp(tProj, 0.0, segLen);
        vec3 closest = tailPos + segN * tProj;
        float angDist = acos(clamp(dot(viewDir, normalize(closest)), 0.0, 1.0));

        // Streak rendering
        float meteorWidth = 0.0015;
        float streak = smoothstep(meteorWidth, meteorWidth * 0.15, angDist);
        if (streak < 0.001) continue;

        // Head-to-tail brightness gradient
        float headGrad = tProj / segLen;
        float core = smoothstep(0.7, 1.0, headGrad) * 2.0;
        float trail = headGrad * 0.6;
        float brightness = (core + trail) * streak * alpha * maxBright;

        // Warm white color
        color += vec3(1.0, 0.890005, 0.603827) * brightness;
    }

    return color;
}

// Linear sRGB nebula emission, baked into the cubemap.
vec3 proceduralNebula(vec3 dir) {
    // Domain warping: perturb the sample coordinate with a low-frequency fBm so
    // the large-scale structure no longer aligns to the noise lattice. This is
    // what turns blocky blobs into organic, drifting filaments. The warp is very
    // low frequency, so a single octave is enough - and since it runs for EVERY
    // sky pixel (before the region early-out), keeping it at 1 octave matters.
    vec3 warp = vec3(
        sf_fbm(dir * 2.0 + vec3(11.3), 1),
        sf_fbm(dir * 2.0 + vec3(47.1), 1),
        sf_fbm(dir * 2.0 + vec3(83.7), 1)
    );
    vec3 wdir = dir + (warp - 0.5) * 0.9;

    float clusterBias = nebulaClusterInfluence(dir);

    // Broad cloud regions (a few across the sky) built from multi-octave fBm
    // rather than a single low-frequency lookup, so edges are soft and varied.
    // Also runs for every sky pixel, so kept to 3 octaves.
    float region = sf_fbm(wdir * 2.5 + vec3(50.0), 3);
    region = smoothstep(0.45, 0.78, (region + clusterBias) * 0.45);

    // Most of the sky has no nebula (region == 0). The remaining detail/wisp/
    // color fBm lookups would just be multiplied by zero there, so bail out
    // early - this is the bulk of the per-pixel savings.
    if (region <= 0.0)
        return vec3(0.0);

    // Finer filamentary structure inside the regions, also fBm + warped.
    float wisps = sf_fbm(wdir * 9.0 + vec3(100.0), 3);
    wisps = smoothstep(0.35, 0.75, wisps);

    // High-frequency texture for graininess near the bright cores.
    float detail = sf_fbm(wdir * 28.0 + vec3(200.0), 2);

    // Combine: region gates everything, wisps carve filaments, detail adds
    // texture. Bias toward region*wisps so the cloud reads as continuous gas
    // rather than scattered specks.
    float nebulaIntensity = region * (0.55 + 0.45 * wisps) * (0.6 + 0.4 * detail) * 1.9;
    nebulaIntensity *= (1.0 + clusterBias * 1.2);

    // Two-tone nebula color: teal dominant with subtle purple variation
    vec3 tealColor = vec3(0.000619195, 0.00193498, 0.00270898);
    float colorVariation = sf_fbm(wdir * 4.0 + vec3(77.0), 2);
    vec3 purpleColor = vec3(0.00154799, 0.000773994, 0.00270898);
    vec3 nebulaColor = mix(tealColor, purpleColor, colorVariation * 0.5);

    return nebulaColor * nebulaIntensity;
}

// The nebula is a static function of direction, so it is baked into a cubemap
// once and sampled cheaply instead of recomputing its fBm per pixel. Normal
// shaders sample the prebaked cubemap; only the bake shader itself defines
// NEBULA_BAKE to evaluate the nebula procedurally (to fill the cubemap).
#ifdef NEBULA_BAKE
    vec3 sampleNebula(vec3 dir) {
        return proceduralNebula(dir);
    }
#elif NEBULAS
    uniform samplerCube nebulaMap;

    vec3 sampleNebula(vec3 dir) {
        return texture(nebulaMap, dir).rgb;
    }
#endif

#if NEBULAS
    // Returns only the background sky color + nebula (no individual stars).
    // Used for fog blending so the fog matches the sky darkness without
    // showing star points through terrain.
    vec3 proceduralStarfieldBackground(vec3 dir) {
        vec3 color = STARFIELD_BACKGROUND_COLOR;
        if (uboSky.nebulaVisibility > 0.0)
            color += sampleNebula(dir) * uboSky.nebulaVisibility;
        return color;
    }

    // The static background needs neither a celestial rotation nor a nebula lookup.
    vec3 nightSkyBackground(vec3 viewDir, float elapsedSeconds) {
        return uboSky.nebulaVisibility == 0.0 ?
            STARFIELD_BACKGROUND_COLOR :
            proceduralStarfieldBackground(rotateStarfield(viewDir, elapsedSeconds, 1.0));
    }
#else
    vec3 nightSkyBackground(vec3 viewDir, float elapsedSeconds) {
        return STARFIELD_BACKGROUND_COLOR;
    }
#endif
