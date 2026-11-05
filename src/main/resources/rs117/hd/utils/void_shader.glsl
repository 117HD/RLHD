#pragma once

#include <utils/starfield.glsl>

#define VOID_UV_SCALE (1.0 / 128.0)
#define VOID_NEAR_DEPTH 0.6
#define VOID_MID_DEPTH 1.6
#define VOID_FAR_DEPTH 3.6
#define VOID_STAR_DENSITY 0.08
#define VOID_STAR_SIZE 0.05
#define VOID_STAR_MIN_RADIUS 1.25
#define VOID_STAR_MAX_RADIUS 4.0
#define VOID_STAR_BRIGHTNESS 4.0

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
    return mix(
        voidStarGaussian(vec2(cell, seed)),
        voidStarGaussian(vec2(cell + 1.0, seed)),
        w
    ) * inversesqrt((1.0 - w) * (1.0 - w) + w * w);
}

vec3 voidStarScintillation(vec2 starHash, vec3 position, float size, float brightness) {
    float rate = 8.59 * mix(0.8, 1.2, starHash.y);
    float seed = starHash.x * 4096.0;
    float time = uboGlobal.elapsedTime * rate + starHash.y;
    vec3 noise = 0.8 * voidStarNoise(time, seed) + 0.6 * voidStarNoise(time * 0.19, seed + 8192.0);
    const float sigma = 0.315;
    vec3 chromaFieldPosition = position + uboGlobal.elapsedTime * vec3(0.017, -0.013, 0.011);
    float chromaHotspot = smoothstep(0.78, 0.95, sf_noise(chromaFieldPosition + vec3(73.0)));
    float chroma = 0.455 * mix(0.2, 1.35, chromaHotspot);
    const vec3 redBlueDispersion = vec3(1.0, 0.0, -1.0);
    const vec3 greenDispersion = vec3(-0.0525, 0.105, -0.0525);
    vec3 logGain = vec3(sigma * noise.x) + chroma * (noise.y * redBlueDispersion + noise.z * greenDispersion);
    const vec3 chromaVariance = redBlueDispersion * redBlueDispersion + greenDispersion * greenDispersion;
    vec3 variance = vec3(sigma * sigma) + chroma * chroma * chromaVariance;
    vec3 scintillation = exp(logGain - 0.5 * variance);
    float prominence = size * sqrt(brightness / 0.4);
    return mix(vec3(1.0), scintillation, 1.0 - 0.45 * prominence);
}

vec3 voidStarLayer(vec2 uv, float cellsPerUv, float density, mat2 pixelsPerUv, float uvPerPixel, float sizeFootprintScale, float seed) {
    // Fade unresolved cells smoothly before skipping them, rather than popping at the cutoff.
    float cellsPerPixel = uvPerPixel * cellsPerUv;
    float visibility = 1.0 - smoothstep(0.25, 0.5, cellsPerPixel);
    if (visibility <= 0.0)
        return vec3(0);

    vec2 grid = uv * cellsPerUv;
    vec2 cell = floor(grid);
    vec4 h = hash42(cell + seed * 1013.0);
    if (h.x >= density)
        return vec3(0);

    // Inset stars to limit clipping when sampling only the current cell.
    vec2 starPos = cell + 0.2 + 0.6 * h.yz;
    vec2 pixelOffset = pixelsPerUv * ((grid - starPos) / cellsPerUv);
    // Scale with the projected cell size. Clamp the rendered radius and dim undersized stars
    // instead of shrinking them below the pixel-stable minimum.
    float starSize = h.w * h.w;
    float trueRadius = VOID_STAR_SIZE * mix(0.6, 1.4, starSize) / max(cellsPerPixel * sizeFootprintScale, 1e-6);
    float radius = clamp(trueRadius, VOID_STAR_MIN_RADIUS, VOID_STAR_MAX_RADIUS);
    float radiusSquared = radius * radius;
    float distanceSquared = dot(pixelOffset, pixelOffset) / radiusSquared;
    // Beyond two radii, the Gaussian is below 2.3e-8 of its peak. Skip the expensive scintillation there.
    if (distanceSquared >= 4.0)
        return vec3(0);
    float falloff = exp(-4.4 * distanceSquared);

    float baseBrightness = min(starSize * sqrt(h.w) * 0.6 + 0.02, 0.4);
    float brightness = baseBrightness * min(1.0, trueRadius * trueRadius / radiusSquared);
    vec3 tint = mix(vec3(0.75, 0.85, 1.0), vec3(1.0, 0.9, 0.7), h.y);
    tint *= voidStarScintillation(hash23(vec3(cell, seed)), vec3(starPos / cellsPerUv * 6.0, seed), starSize, baseBrightness);
    return tint * brightness * falloff * visibility * VOID_STAR_BRIGHTNESS;
}

// Linear sRGB of a starry void seen into a surface: a dark background & stars, layered at
// depths behind the surface. World projection is continuous between faces using the same dominant axis;
// changing the dominant axis can produce seams. viewDir points to the camera.
// dPdx & dPdy are position derivatives evaluated outside divergent control flow.
vec3 sampleVoidShader(vec3 position, vec3 normal, vec3 viewDir, vec3 dPdx, vec3 dPdy, float density) {
    vec3 a = abs(normal);
    vec2 uv;
    vec3 viewTS;
    mat2 uvDerivatives;
    if (a.y >= a.x && a.y >= a.z) {
        uv = position.xz;
        viewTS = viewDir.xzy;
        uvDerivatives = mat2(dPdx.xz, dPdy.xz);
    } else if (a.x >= a.z) {
        uv = position.zy;
        viewTS = viewDir.zyx;
        uvDerivatives = mat2(dPdx.zy, dPdy.zy);
    } else {
        uv = position.xy;
        viewTS = viewDir;
        uvDerivatives = mat2(dPdx.xy, dPdy.xy);
    }
    uv *= VOID_UV_SCALE;
    uvDerivatives *= VOID_UV_SCALE;
    float det = determinant(uvDerivatives);
    if (abs(det) < 4e-12)
        return STARFIELD_BACKGROUND_COLOR;
    mat2 pixelsPerUv = inverse(uvDerivatives);
    // Square root of the UV footprint area, used as an isotropic size estimate.
    float uvPerPixel = sqrt(abs(det));

    // Partially undo foreshortening for dense voids' star sizes, not their positions or resolution cutoff.
    // Leave sparse voids and head-on views unchanged, and bound the boost at grazing angles.
    float viewCosine = abs(viewTS.z);
    float compensation = smoothstep(1.0, 2.0, density) * (1.0 - smoothstep(0.15, 0.6, viewCosine));
    float sizeFootprintScale = mix(1.0, sqrt(max(viewCosine, 0.25)), compensation);

    // UV offset per unit of depth, limited at grazing angles
    vec2 shift = -viewTS.xy / max(viewCosine, 0.25);

    vec3 color = STARFIELD_BACKGROUND_COLOR;
    // Nearer layers have fewer, larger-looking stars
    color += voidStarLayer(uv + shift * VOID_NEAR_DEPTH, 3.0, VOID_STAR_DENSITY * density,
        pixelsPerUv, uvPerPixel, sizeFootprintScale, 1.0);
    color += voidStarLayer(uv + shift * VOID_MID_DEPTH, 5.0, VOID_STAR_DENSITY * density,
        pixelsPerUv, uvPerPixel, sizeFootprintScale, 2.0) * 0.7;
    color += voidStarLayer(uv + shift * VOID_FAR_DEPTH, 8.0, VOID_STAR_DENSITY * density,
        pixelsPerUv, uvPerPixel, sizeFootprintScale, 3.0) * 0.5;
    return color;
}
