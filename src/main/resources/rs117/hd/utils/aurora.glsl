#pragma once

#include <utils/hash.glsl>

// Volumetric aurora curtains, raymarched through an elevated slab of sky.
//
// Each curtain is a thin vertical sheet: a folded ribbon in the horizontal
// plane, extruded upward and sheared as it rises. Marching the view ray
// through the slab, rather than intersecting a handful of flat planes, is what
// gives the curtains height -- near parts of a sheet sweep past faster than far
// parts as the camera turns, the rays stay standing in the volume, and the
// colour can ramp from green along the base to magenta at the tips.

// The slab the curtains live in: altitudes above the camera, in world units.
#define AURORA_BASE 900.0
#define AURORA_TOP 4500.0
// Samples taken per ray between base and top. The main quality/cost knob.
#define AURORA_STEPS 20
// World units per unit of curtain coordinate: one fold spans roughly 7000.
#define AURORA_SCALE 0.0005
// Horizontal distance at which curtains are lost to haze, in world units.
#define AURORA_VIEW_DISTANCE 24000.0
#define AURORA_INTENSITY 0.7

float au_hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

float au_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(au_hash21(i), au_hash21(i + vec2(1.0, 0.0)), f.x),
        mix(au_hash21(i + vec2(0.0, 1.0)), au_hash21(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

// Return the curtain ridge at a horizontal position.
float auroraCurtain(float x, float time) {
    float wave = sin(x * 1.8 - time * 0.4) * 0.5;
    wave += sin(x * 3.1 + time * 0.25) * 0.25;
    wave += sin(x * 7.0 - time * 0.6) * 0.08;
    wave += au_noise(vec2(x * 0.8 + time * 0.05, time * 0.03)) * 0.4;
    return wave;
}

// Centre of a sheet at length coordinate u and height fraction h. The shear
// with height keeps the sheet from being a straight vertical extrusion, so it
// still reads as a surface in depth when seen close to edge-on.
float auroraRibbon(float u, float h, float time, float seed) {
    float lean = 0.05 + 0.16 * sin(u * 2.3 - time * 0.18 + seed);
    return auroraCurtain(u, time + seed * 5.0) + lean * h;
}

// Emissive colour of one curtain sheet at a point in the slab.
//   h          height fraction through the slab, 0 at the base
//   sigmaFloor minimum sheet half-width, set from the march footprint so that
//              distant curtains prefilter instead of sparkling
//   detail     how much fine ray structure survives at that footprint
vec3 auroraSheet(vec3 p, float h, float sigmaFloor, float detail, float time, float seed) {
    float u = p.x * AURORA_SCALE + seed * 3.7;
    float v = p.z * AURORA_SCALE;

    // Curtains do not all reach the same height. Varying the reach along the
    // ribbon is most of what sells them as separate sheets hanging in the sky.
    float reach = mix(0.45, 1.3, au_noise(vec2(u * 1.1 + seed * 7.0, time * 0.03 + seed)));
    float hn = h / reach; // 0 at the base, 1 at this curtain's tip
    if (hn >= 1.0) return vec3(0.0);

    // Sharp lower border, long fade toward the tips.
    float vProfile = smoothstep(0.0, 0.10, hn) * exp(-hn * 1.5) * (1.0 - smoothstep(0.7, 1.0, hn));

    // Sheets thicken and diffuse as they rise. The peak scales with 1/sigma so
    // a widened (prefiltered) sheet emits as much light as a crisp one rather
    // than getting brighter.
    float sigma = max(mix(0.030, 0.075, hn), sigmaFloor);
    float d = v - auroraRibbon(u, hn, time, seed);
    float sheet = exp(-d * d / (2.0 * sigma * sigma)) * (0.030 / sigma);

    float density = sheet * vProfile;
    if (density < 1e-3) return vec3(0.0);

    // Vertical rays. Constant along a curtain's height, so they read as
    // structure standing in the volume instead of texture on a plane.
    float rays = au_noise(vec2(u * 9.0 + seed * 10.0, time * 0.1));
    rays = mix(0.45, 1.0, smoothstep(0.15, 0.85, rays));
    float fine = au_noise(vec2(u * 30.0 - time * 0.25, seed * 3.0 + hn * 0.35));
    rays *= mix(1.0, mix(0.6, 1.2, fine), detail);

    float brightness = 0.4 + 0.6 * au_noise(vec2(u * 1.7 - time * 0.02, seed));

    // Slowly vary the emissive green base between teal and yellow-green, and
    // ramp to magenta at the tips the way oxygen gives way to nitrogen.
    float hueNoise = au_noise(vec2(u * 1.3 + seed * 4.0, time * 0.04 + seed));
    vec3 tealGreen = vec3(0.0, 1.5, 0.9);   // cool cyan-green
    vec3 warmGreen = vec3(0.35, 1.7, 0.25); // warm yellow-green
    vec3 green = mix(tealGreen, warmGreen, smoothstep(0.25, 0.75, hueNoise));
    vec3 magenta = vec3(1.0, 0.15, 1.25);
    vec3 col = mix(green, magenta, smoothstep(0.3, 0.95, hn));

    return col * density * rays * brightness;
}

// Return additive aurora color in linear sRGB.
vec3 proceduralAurora(vec3 viewDir, float time) {
    float upAmount = -viewDir.y;

    if (upAmount < 0.01) return vec3(0.0);

    // North-facing gate: aurora in the northern sky (+Z)
    float northFacing = viewDir.z;
    if (northFacing < -0.2) return vec3(0.0);
    float northBlend = smoothstep(-0.2, 0.3, northFacing);

    float horizonFade = smoothstep(0.01, 0.06, upAmount);

    vec3 rd = normalize(viewDir);
    float up = max(-rd.y, 1e-3);

    // Enter the slab at its base and step up through it in equal heights.
    float tBase = AURORA_BASE / up;
    float dt = (AURORA_TOP - AURORA_BASE) / (up * float(AURORA_STEPS));

    // Horizontal footprint of one step, along the curtain coordinate. Sheets
    // and ray detail are filtered against it, so the long steps a grazing ray
    // takes blur the curtains rather than aliasing them into sparkle.
    float stepU = dt * length(rd.xz) * AURORA_SCALE;
    float sigmaFloor = stepU * 0.5;
    float detail = 1.0 - smoothstep(0.03, 0.10, stepU);

    // Offset the march per pixel so a short march does not band.
    float jitter = hash12(gl_FragCoord.xy);

    vec3 aurora = vec3(0.0);
    for (int i = 0; i < AURORA_STEPS; i++) {
        vec3 p = rd * (tBase + (float(i) + jitter) * dt);
        float h = (-p.y - AURORA_BASE) / (AURORA_TOP - AURORA_BASE);

        // Haze swallows the far ends of the curtains, leaving the bright lower
        // border arcing across the northern sky.
        float r = length(p.xz) / AURORA_VIEW_DISTANCE;
        float haze = exp(-r * r);
        if (haze < 0.01) break; // only ever gets further away

        aurora += auroraSheet(p, h, sigmaFloor, detail, time, 1.0) * haze;
        aurora += auroraSheet(p, h, sigmaFloor, detail, time, 2.6) * haze * 0.7;
    }

    // Weight by the path length each step covers, capped so curtains brighten
    // toward the horizon without running away as the ray goes flat.
    aurora *= min(1.0 / up, 6.0) / float(AURORA_STEPS);

    return aurora * northBlend * horizonFade * AURORA_INTENSITY;
}
