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
    f = f * f * (3.0 - 2.0 * f);

    return mix(
        mix(mix(sf_hash(i + vec3(0,0,0)), sf_hash(i + vec3(1,0,0)), f.x),
            mix(sf_hash(i + vec3(0,1,0)), sf_hash(i + vec3(1,1,0)), f.x), f.y),
        mix(mix(sf_hash(i + vec3(0,0,1)), sf_hash(i + vec3(1,0,1)), f.x),
            mix(sf_hash(i + vec3(0,1,1)), sf_hash(i + vec3(1,1,1)), f.x), f.y),
        f.z
    );
}

vec3 proceduralStarfield(vec3 dir) {
    // Near-black background with faint blue tint
    vec3 color = vec3(0.00304, 0.00304, 0.00521);

    // --- STAR GENERATION ---
    // Two layers: bright/sparse + dim/dense for visual depth
    vec3 starColor = vec3(0.0);

    for (int layer = 0; layer < 2; layer++) {
        float gridScale = (layer == 0) ? 80.0 : 200.0;
        float sparsity = (layer == 0) ? 0.82 : 0.76;
        float maxBrightness = (layer == 0) ? 1.2 : 0.4;
        float starRadius = (layer == 0) ? 0.2 : 0.15;

        // Project direction onto 3D grid
        vec3 scaledDir = dir * gridScale;
        vec3 cell = floor(scaledDir);

        // Check current cell and 26 neighbors to prevent boundary artifacts
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    vec3 neighborCell = cell + vec3(dx, dy, dz);

                    // Does this cell contain a star?
                    float cellRand = sf_hash(neighborCell);
                    if (cellRand < sparsity) continue;

                    // Random position within cell
                    vec3 starPos = neighborCell + sf_hash3(neighborCell);

                    // Distance from current point to star
                    float dist = length(starPos - scaledDir);
                    if (dist > starRadius) continue;

                    // Power-law brightness (many dim, few bright)
                    float brightnessSeed = (cellRand - sparsity) / (1.0 - sparsity);
                    float brightness = pow(brightnessSeed, 2.5) * maxBrightness;

                    // Very sharp point-spread falloff for crisp stars
                    float falloff = 1.0 - smoothstep(0.0, starRadius, dist);
                    falloff = pow(falloff, 4.0);
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

    // --- NEBULA / MILKY WAY ---
    // A few large sweeping regions with wispy internal structure
    {
        // Very low frequency — creates only 2-3 broad regions across the sky
        float nebulaRegion = sf_noise(dir * 2.5 + vec3(50.0));
        nebulaRegion = smoothstep(0.52, 0.8, nebulaRegion);

        // Internal structure at medium scale
        float wisps = sf_noise(dir * 12.0 + vec3(100.0));
        wisps = smoothstep(0.3, 0.7, wisps);

        // Softer detail within the regions
        float detail = sf_noise(dir * 30.0 + vec3(200.0)) * 0.7
                      + sf_noise(dir * 60.0 + vec3(300.0)) * 0.3;

        float nebulaIntensity = nebulaRegion * wisps * detail * 1.8;

        // Two-tone nebula color: teal dominant with subtle purple variation
        vec3 tealColor = vec3(0.008, 0.025, 0.035);
        float colorVariation = sf_noise(dir * 5.0 + vec3(77.0));
        vec3 purpleColor = vec3(0.02, 0.01, 0.035);
        vec3 nebulaColor = mix(tealColor, purpleColor, colorVariation * 0.3);

        color += nebulaColor * nebulaIntensity;
    }

    return color;
}

// Returns only the background sky color + nebula (no individual stars).
// Used for fog blending so the fog matches the sky darkness without
// showing star points through terrain.
vec3 proceduralStarfieldBackground(vec3 dir) {
    vec3 color = vec3(0.00304, 0.00304, 0.00521);

    float nebulaRegion = sf_noise(dir * 2.5 + vec3(50.0));
    nebulaRegion = smoothstep(0.52, 0.8, nebulaRegion);

    float wisps = sf_noise(dir * 12.0 + vec3(100.0));
    wisps = smoothstep(0.3, 0.7, wisps);

    float detail = sf_noise(dir * 30.0 + vec3(200.0)) * 0.7
                  + sf_noise(dir * 60.0 + vec3(300.0)) * 0.3;

    float nebulaIntensity = nebulaRegion * wisps * detail * 1.8;

    vec3 tealColor = vec3(0.008, 0.025, 0.035);
    float colorVariation = sf_noise(dir * 5.0 + vec3(77.0));
    vec3 purpleColor = vec3(0.02, 0.01, 0.035);
    vec3 nebulaColor = mix(tealColor, purpleColor, colorVariation * 0.3);

    color += nebulaColor * nebulaIntensity;

    return color;
}
