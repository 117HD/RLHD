#pragma once

// Procedural starfield generator
// Operates on a 3D direction vector for seamless spherical mapping
// with no polar distortion artifacts.

// Private hash functions (sf_ prefix avoids conflicts with misc.glsl)
float sf_hash(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453123);
}

vec3 sf_hash3(vec3 p) {
    return vec3(
        fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453),
        fract(sin(dot(p, vec3(269.5, 183.3, 246.1))) * 43758.5453),
        fract(sin(dot(p, vec3(113.5, 271.9, 124.6))) * 43758.5453)
    );
}

float sf_noise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    // Quintic interpolant (6t^5 - 15t^4 + 10t^3). Unlike the cubic smoothstep,
    // its 1st AND 2nd derivatives vanish at cell boundaries, so adjacent cells
    // join without the visible faceting/creasing that makes value noise look blocky.
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    return mix(
        mix(mix(sf_hash(i + vec3(0,0,0)), sf_hash(i + vec3(1,0,0)), f.x),
            mix(sf_hash(i + vec3(0,1,0)), sf_hash(i + vec3(1,1,0)), f.x), f.y),
        mix(mix(sf_hash(i + vec3(0,0,1)), sf_hash(i + vec3(1,0,1)), f.x),
            mix(sf_hash(i + vec3(0,1,1)), sf_hash(i + vec3(1,1,1)), f.x), f.y),
        f.z
    );
}

// Fractal Brownian Motion — sums octaves of noise at increasing frequency and
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

// Procedural shooting stars — returns additive color contribution
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

        // ~12% spawn chance per slot → ~1 meteor per 42s average
        if (sf_hash(seed) > 0.12) continue;

        // Start position on upper sky sphere
        float theta = sf_hash(seed + vec3(1.0, 0.0, 0.0)) * TAU;
        float cosElev = 1.0 - sf_hash(seed + vec3(2.0, 0.0, 0.0)) * 0.65;
        float sinElev = sqrt(1.0 - cosElev * cosElev);
        vec3 startPos = normalize(vec3(sinElev * cos(theta), -cosElev, sinElev * sin(theta)));

        // Travel direction (generally downward with randomization)
        float tTheta = sf_hash(seed + vec3(3.0, 0.0, 0.0)) * TAU;
        float tPhi = 0.3 + sf_hash(seed + vec3(4.0, 0.0, 0.0)) * 0.5;
        vec3 travelDir = normalize(vec3(
            sin(tPhi) * cos(tTheta),
            cos(tPhi),
            sin(tPhi) * sin(tTheta)
        ));

        float speed = 0.08 + sf_hash(seed + vec3(5.0, 0.0, 0.0)) * 0.06;
        float lifetime = 0.8 + sf_hash(seed + vec3(6.0, 0.0, 0.0)) * 0.7;
        float maxBright = 0.6 + sf_hash(seed + vec3(7.0, 0.0, 0.0)) * 0.6;

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
        color += vec3(1.0, 0.95, 0.8) * brightness;
    }

    return color;
}

// Shared nebula contribution, used by both the full starfield and the
// background-only variant so they stay perfectly in sync.
vec3 proceduralNebula(vec3 dir) {
    // Domain warping: perturb the sample coordinate with a low-frequency fBm so
    // the large-scale structure no longer aligns to the noise lattice. This is
    // what turns blocky blobs into organic, drifting filaments. The warp is
    // low frequency, so 2 octaves are plenty — keeps the per-pixel cost down.
    vec3 warp = vec3(
        sf_fbm(dir * 2.0 + vec3(11.3), 2),
        sf_fbm(dir * 2.0 + vec3(47.1), 2),
        sf_fbm(dir * 2.0 + vec3(83.7), 2)
    );
    vec3 wdir = dir + (warp - 0.5) * 0.9;

    // Broad cloud regions (a few across the sky) built from multi-octave fBm
    // rather than a single low-frequency lookup, so edges are soft and varied.
    float region = sf_fbm(wdir * 2.5 + vec3(50.0), 5);
    region = smoothstep(0.45, 0.78, region);

    // Most of the sky has no nebula (region == 0). The remaining detail/wisp/
    // color fBm lookups (~10 noise samples) would just be multiplied by zero
    // there, so bail out early — this is the bulk of the per-pixel savings.
    if (region <= 0.0)
        return vec3(0.0);

    // Finer filamentary structure inside the regions, also fBm + warped.
    float wisps = sf_fbm(wdir * 9.0 + vec3(100.0), 4);
    wisps = smoothstep(0.35, 0.75, wisps);

    // High-frequency texture for graininess near the bright cores.
    float detail = sf_fbm(wdir * 28.0 + vec3(200.0), 3);

    // Combine: region gates everything, wisps carve filaments, detail adds
    // texture. Bias toward region*wisps so the cloud reads as continuous gas
    // rather than scattered specks.
    float nebulaIntensity = region * (0.55 + 0.45 * wisps) * (0.6 + 0.4 * detail) * 1.9;

    // Two-tone nebula color: teal dominant with subtle purple variation
    vec3 tealColor = vec3(0.008, 0.025, 0.035);
    float colorVariation = sf_fbm(wdir * 4.0 + vec3(77.0), 3);
    vec3 purpleColor = vec3(0.02, 0.01, 0.035);
    vec3 nebulaColor = mix(tealColor, purpleColor, colorVariation * 0.5);

    return nebulaColor * nebulaIntensity;
}

vec3 proceduralStarfield(vec3 dir) {
    // Near-black background with faint blue tint
    vec3 color = vec3(0.00304, 0.00304, 0.00521);

    // Star Generation
    // Two layers: bright/sparse + dim/dense for visual depth
    vec3 starColor = vec3(0.0);

    for (int layer = 0; layer < 2; layer++) {
        float gridScale = (layer == 0) ? 80.0 : 200.0;
        float sparsity = (layer == 0) ? 0.82 : 0.76;
        float maxBrightness = (layer == 0) ? 1.2 : 0.4;
        float starRadius = (layer == 0) ? 0.45 : 0.30;

        // Project direction onto 3D grid
        vec3 scaledDir = dir * gridScale;
        vec3 cell = floor(scaledDir);

        // A star lives at cell + sf_hash3(cell) (offset in [0,1) per axis) and has
        // max effective radius starRadius * 1.0 < 0.5. So along each axis the query
        // point can only be reached by a star in its own cell or the ONE neighbor on
        // the side the fractional position leans toward. That's an 8-cell footprint
        // (own cell + nearer neighbor per axis) instead of the full 27, and it is
        // bit-identical to the 27-cell search while max starRadius < 0.5.
        // EXACT ONLY WHILE max starRadius < 0.5 — current max is 0.45.
        vec3 frac = scaledDir - cell;                 // in [0,1)
        // step() gives 0 for frac<0.5 and 1 for frac>=0.5; map to -1/+1 so the offset
        // is always non-zero (avoids re-testing the own cell, which would double-count).
        // At exactly frac==0.5 the chosen neighbor can't contain a reaching star
        // (any star there is >=0.5 away > starRadius), so the +1 tie-break is safe.
        vec3 nearStep = step(0.5, frac) * 2.0 - 1.0;  // -1 toward lower neighbor, +1 toward upper
        // Iterate the 2x2x2 footprint: index 0 = own cell, index 1 = nearer neighbor.
        for (int ix = 0; ix <= 1; ix++) {
            for (int iy = 0; iy <= 1; iy++) {
                for (int iz = 0; iz <= 1; iz++) {
                    vec3 neighborCell = cell + vec3(ix, iy, iz) * nearStep;

                    // Does this cell contain a star?
                    float cellRand = sf_hash(neighborCell);
                    if (cellRand < sparsity) continue;

                    // Random position within cell
                    vec3 starPos = neighborCell + sf_hash3(neighborCell);

                    // Per-star size variation (0.5x to 1.0x of layer radius)
                    float sizeSeed = sf_hash(neighborCell + vec3(99.0));
                    float sizeScale = 0.5 + sizeSeed * 0.5;
                    float thisRadius = starRadius * sizeScale;

                    // Distance from current point to star
                    float dist = length(starPos - scaledDir);
                    if (dist > thisRadius) continue;

                    // Power-law brightness (many dim, few bright)
                    float brightnessSeed = (cellRand - sparsity) / (1.0 - sparsity);
                    float brightness = pow(brightnessSeed, 2.5) * maxBrightness;

                    // Very sharp point-spread falloff for crisp stars
                    float falloff = 1.0 - smoothstep(0.0, thisRadius, dist);
                    falloff = pow(falloff, 8.0);
                    brightness *= falloff;

                    // Color variation matching natural stellar populations
                    float colorSeed = sf_hash(neighborCell + vec3(42.0));
                    vec3 tint;
                    if (colorSeed < 0.06) {
                        tint = vec3(1.0, 0.7, 0.45);  // warm orange
                    } else if (colorSeed < 0.18) {
                        tint = vec3(1.0, 0.9, 0.65);  // golden yellow
                    } else if (colorSeed < 0.30) {
                        tint = vec3(1.0, 0.95, 0.85); // pale warm white
                    } else if (colorSeed < 0.70) {
                        tint = vec3(1.0);              // neutral white
                    } else if (colorSeed < 0.85) {
                        tint = vec3(0.85, 0.92, 1.0); // pale blue-white
                    } else {
                        tint = vec3(0.7, 0.8, 1.0);   // cool blue
                    }

                    starColor += tint * brightness;
                }
            }
        }
    }

    color += starColor;

    // Nebulas
    // A few large sweeping regions with wispy internal structure.
    // Skip the (expensive) nebula evaluation entirely when it's disabled.
    if (nebulaVisibility > 0.0)
        color += proceduralNebula(dir) * nebulaVisibility;

    return color;
}

// Returns only the background sky color + nebula (no individual stars).
// Used for fog blending so the fog matches the sky darkness without
// showing star points through terrain.
vec3 proceduralStarfieldBackground(vec3 dir) {
    vec3 color = vec3(0.00304, 0.00304, 0.00521);
    if (nebulaVisibility > 0.0)
        color += proceduralNebula(dir) * nebulaVisibility;
    return color;
}
