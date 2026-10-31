#pragma once

#include <utils/starfield.glsl>

// The void is projected onto world position, since vanilla UVs flip between faces. UV units per world unit (a tile is 128).
#define VOID_UV_SCALE (1.0 / 256.0)
// How far behind the surface each star layer sits, in UV units. Deeper layers shift more as the camera moves.
#define VOID_NEAR_DEPTH 0.3
#define VOID_MID_DEPTH 0.8
#define VOID_FAR_DEPTH 1.8
// Fraction of cells holding a star
#define VOID_STAR_DENSITY 0.08
// Star radius as a fraction of its cell, so stars shrink with the surface as the camera zooms out
#define VOID_STAR_SIZE 0.05

// Unit Gaussians & smooth noise from star_vert, for the sky stars' scintillation
vec3 voidStarGaussian(vec2 seed) {
    vec4 u = hash42(seed);
    vec2 radius = sqrt(-2.0 * log(max(u.xy, vec2(1e-6))));
    vec2 angle = TAU * u.zw;
    return vec3(radius.x * cos(angle.x), radius.x * sin(angle.x), radius.y * cos(angle.y));
}

vec3 voidStarNoise(float time, float seed) {
    float cell = floor(time);
    float t = fract(time);
    float w = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    return mix(voidStarGaussian(vec2(cell, seed)), voidStarGaussian(vec2(cell + 1.0, seed)), w)
        * inversesqrt((1.0 - w) * (1.0 - w) + w * w);
}

// The sky stars' twinkling from star_vert: irregular lognormal flicker with chromatic dispersion,
// steadier for larger & brighter stars. position drifts the patches where dispersion is strongest.
vec3 voidStarScintillation(vec2 starHash, vec3 position, float size, float brightness) {
    // star_vert's tuning, worked out: rate, log-intensity deviation & chromatic deviation
    float rate = 8.59 * mix(0.8, 1.2, starHash.y);
    float seed = starHash.x * 4096.0;
    float time = uboGlobal.elapsedTime * rate + starHash.y;
    vec3 noise = 0.8 * voidStarNoise(time, seed) + 0.6 * voidStarNoise(time * 0.19, seed + 8192.0);
    float sigma = 0.315;
    vec3 chromaFieldPosition = position + uboGlobal.elapsedTime * vec3(0.017, -0.013, 0.011);
    float chromaHotspot = smoothstep(0.78, 0.95, sf_noise(chromaFieldPosition + vec3(73.0)));
    float chroma = 0.455 * mix(0.2, 1.35, chromaHotspot);
    const vec3 redBlueDispersion = vec3(1.0, 0.0, -1.0);
    const vec3 greenDispersion = vec3(-0.0525, 0.105, -0.0525);
    vec3 logGain = vec3(sigma * noise.x) + chroma * (noise.y * redBlueDispersion + noise.z * greenDispersion);
    vec3 chromaVariance = redBlueDispersion * redBlueDispersion + greenDispersion * greenDispersion;
    vec3 variance = vec3(sigma * sigma) + chroma * chroma * chromaVariance;
    vec3 scintillation = exp(logGain - 0.5 * variance);
    float prominence = clamp(size, 0.0, 1.0) * sqrt(clamp(brightness / 0.4, 0.0, 1.0));
    return mix(vec3(1.0), scintillation, 1.0 - 0.45 * prominence);
}

// One layer of stars on a grid in UV space. pixelsPerUv maps UV offsets to screen pixels, accounting for the surface's angle.
vec3 voidStarLayer(vec2 uv, float cellsPerUv, mat2 pixelsPerUv, float uvPerPixel, float seed) {
    vec2 grid = uv * cellsPerUv;
    vec2 cell = floor(grid);
    vec4 h = hash42(cell + seed * 1013.0);
    if (h.x > VOID_STAR_DENSITY)
        return vec3(0);

    // Skip cells under 2 pixels across, where stars have already dimmed to nothing
    float cellsPerPixel = uvPerPixel * cellsPerUv;
    if (cellsPerPixel > 0.5)
        return vec3(0);

    // Stars sit away from cell edges, so a cell only needs to check its own
    vec2 starPos = cell + 0.2 + 0.6 * h.yz;
    float pixels = length(pixelsPerUv * ((grid - starPos) / cellsPerUv));
    // Like the sky's own star sprites, keep radii within a small pixel range, with the same falloff, and dim stars
    // below that rather than shrinking them, so they neither balloon when close nor blur or flicker when far
    float trueRadius = VOID_STAR_SIZE * mix(0.6, 1.4, h.w * h.w) / max(cellsPerPixel, 1e-6);
    float radius = clamp(trueRadius, 1.25, 4.0);
    float d = pixels / radius;
    float falloff = exp(-d * d * 4.4);

    float baseBrightness = min(pow(h.w, 2.5) * 0.6 + 0.02, 0.4);
    float brightness = baseBrightness * min(1.0, trueRadius * trueRadius / (radius * radius));
    vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.9, 0.7), h.y);
    vec3 twinkle = voidStarScintillation(hash23(vec3(cell, seed)), vec3(starPos / cellsPerUv * 12.0, seed), h.w * h.w, baseBrightness);
    return tint * twinkle * brightness * falloff * 4.0;
}

// Linear sRGB of a starry void seen into a surface: a dark background & stars, layered at
// depths behind the surface. Projected along the axis the surface faces most, so it's continuous across faces.
// viewDir points to the camera. dPdx & dPdy are the position's screen derivatives, from outside any branching,
// where derivatives are undefined.
vec3 sampleVoidShader(vec3 position, vec3 normal, vec3 viewDir, vec3 dPdx, vec3 dPdy) {
    vec3 a = abs(normal);
    vec2 uv;
    vec3 viewTS;
    mat2 uvPerPixelXY;
    if (a.y >= a.x && a.y >= a.z) {
        uv = position.xz;
        viewTS = viewDir.xzy;
        uvPerPixelXY = mat2(dPdx.xz, dPdy.xz);
    } else if (a.x >= a.z) {
        uv = position.zy;
        viewTS = viewDir.zyx;
        uvPerPixelXY = mat2(dPdx.zy, dPdy.zy);
    } else {
        uv = position.xy;
        viewTS = viewDir;
        uvPerPixelXY = mat2(dPdx.xy, dPdy.xy);
    }
    uv *= VOID_UV_SCALE;
    uvPerPixelXY *= VOID_UV_SCALE;
    float det = determinant(uvPerPixelXY);
    if (abs(det) < 1e-12)
        return STARFIELD_BACKGROUND_COLOR;
    mat2 pixelsPerUv = inverse(uvPerPixelXY);
    // The geometric mean of the UV footprint's sides, for sizes
    float uvPerPixel = sqrt(abs(det));

    // UV offset per unit of depth, limited at grazing angles
    vec2 shift = -viewTS.xy / max(abs(viewTS.z), 0.25);

    vec3 color = STARFIELD_BACKGROUND_COLOR;
    // Nearer layers have fewer, larger-looking stars
    color += voidStarLayer(uv + shift * VOID_NEAR_DEPTH, 6.0, pixelsPerUv, uvPerPixel, 1.0);
    color += voidStarLayer(uv + shift * VOID_MID_DEPTH, 10.0, pixelsPerUv, uvPerPixel, 2.0) * 0.7;
    color += voidStarLayer(uv + shift * VOID_FAR_DEPTH, 16.0, pixelsPerUv, uvPerPixel, 3.0) * 0.5;
    return color;
}
