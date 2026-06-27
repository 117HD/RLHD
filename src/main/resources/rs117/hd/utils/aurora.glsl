#pragma once

// Procedural aurora borealis — raymarched curtain waves
// Traces view rays through thin elevated layers to produce
// distinctive vertical-ray, horizontal-wave aurora curtains.

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

// Deformed wave that defines the aurora curtain shape at a given horizontal position
// Returns a 1D "ridge" value: high where the curtain is, low elsewhere
float auroraCurtain(float x, float time) {
    // Primary travelling sine wave
    float wave = sin(x * 1.8 - time * 0.4) * 0.5;
    // Secondary wave for complexity
    wave += sin(x * 3.1 + time * 0.25) * 0.25;
    // Tertiary fine ripple
    wave += sin(x * 7.0 - time * 0.6) * 0.08;
    // Noise-based warp for organic shape
    wave += au_noise(vec2(x * 0.8 + time * 0.05, time * 0.03)) * 0.4;
    return wave;
}

// Sample a single aurora layer at a given plane height
// ro: ray origin, rd: ray direction, planeY: height of the aurora plane
// time: animation time, layerSeed: offset to differentiate layers
vec3 auroraLayer(vec3 ro, vec3 rd, float planeY, float time, float layerSeed) {
    // Intersect ray with horizontal plane at y = planeY
    // Ray equation: p = ro + rd * t  =>  planeY = ro.y + rd.y * t
    if (rd.y >= 0.0) return vec3(0.0); // ray going down, won't hit sky plane
    float t = (planeY - ro.y) / rd.y;
    if (t < 0.0) return vec3(0.0);

    // Hit point on the aurora plane
    vec3 hit = ro + rd * t;

    // Use hit.x as the horizontal curtain coordinate
    // and hit.z as a secondary axis (depth into curtain)
    float curtainX = hit.x * 0.0005 + layerSeed * 3.7;
    float curtainZ = hit.z * 0.0005;

    // Get the curtain wave position at this x coordinate
    float wave = auroraCurtain(curtainX, time + layerSeed * 5.0);

    // Distance from the curtain center line — this creates the thin ribbon shape
    float distFromCurtain = abs(curtainZ - wave);

    // Sharp falloff away from the curtain line — thin ribbon, not a blob
    float curtainMask = exp(-distFromCurtain * distFromCurtain * 40.0);

    // Vertical ray structure: use noise along the curtain to create
    // the characteristic individual "rays" hanging down
    float rayNoise = au_noise(vec2(curtainX * 12.0 + layerSeed * 10.0, time * 0.1));
    float rays = smoothstep(0.2, 0.8, rayNoise);

    // Brightness variation along the curtain — some sections brighter
    float brightness = au_noise(vec2(curtainX * 2.0 - time * 0.02, layerSeed));
    brightness = 0.4 + brightness * 0.6;

    // Fade with distance to prevent infinite plane look
    float distFade = exp(-t * t * 0.0000001);

    float intensity = curtainMask * rays * brightness * distFade;

    // Color: green base with purple/blue tips
    // Use a proxy for "height within the curtain" based on the plane height
    float heightBlend = (planeY - 600.0) / 600.0; // 0 at bottom, 1 at top of aurora range
    vec3 green = vec3(0.15, 1.0, 0.4);
    vec3 purple = vec3(0.5, 0.15, 0.8);
    vec3 col = mix(green, purple, smoothstep(0.0, 1.0, heightBlend));

    return col * intensity;
}

// Main entry point — returns additive aurora color
vec3 proceduralAurora(vec3 viewDir, float time) {
    float upAmount = -viewDir.y;

    // Only render when looking above the horizon
    if (upAmount < 0.01) return vec3(0.0);

    // North-facing gate: aurora in the northern sky (+Z)
    float northFacing = viewDir.z;
    if (northFacing < -0.2) return vec3(0.0);
    float northBlend = smoothstep(-0.2, 0.3, northFacing);

    // Horizon fade — don't render right at horizon to blend with fog
    float horizonFade = smoothstep(0.01, 0.06, upAmount);

    // Place camera at origin, aurora planes high above
    vec3 ro = vec3(0.0, 0.0, 0.0);
    vec3 rd = normalize(viewDir);

    // Sample multiple horizontal planes at different heights
    // This creates depth and the layered curtain look
    vec3 aurora = vec3(0.0);
    aurora += auroraLayer(ro, rd, -400.0, time, 0.0) * 0.5;
    aurora += auroraLayer(ro, rd, -600.0, time, 1.0) * 0.7;
    aurora += auroraLayer(ro, rd, -800.0, time, 2.0) * 1.0;
    aurora += auroraLayer(ro, rd, -1000.0, time, 3.0) * 0.6;
    aurora += auroraLayer(ro, rd, -1200.0, time, 4.0) * 0.3;

    aurora *= northBlend * horizonFade;

    // Overall brightness — visible but not overpowering
    return aurora * 0.08;
}
