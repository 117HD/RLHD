#pragma once

#include <uniforms/global.glsl>

#include <utils/constants.glsl>
#include <utils/hash.glsl>

// Where and when the ocean waves form. Shared by the water's fragment shader, which draws them, and the scene's vertex
// shader, which lists the waves near each water face, so the two always agree.

#define OCEAN_FOAM_DRIFT_SPEED 0.08        // in tiles per second, scaled by the wind speed
#define OCEAN_WAVE_STRETCH 1.5             // along the wind, 1 for none
#define OCEAN_WAVE_SIZE 7.0                // spacing, in tiles
#define OCEAN_WAVE_AMOUNT 0.45             // chance of each spot having a wave each lifetime, from 0 to 1
#define OCEAN_WAVE_LIFETIME 14.0           // longest life, in seconds
#define OCEAN_WAVE_RATE_RANDOMNESS 0.4     // how much faster some waves live, from 0 to 1
#define OCEAN_WAVE_WIGGLE 1.0              // how wiggly the fronts are
#define OCEAN_WAVE_RAGGEDNESS 1.5          // how ragged the edges and breakup are
#define SHORE_WAVE_MIN_ROOM 5.0            // open water needed nearby for shore and ocean waves, in tiles
// Fewer waves spawn far from the camera, where faces are tiny and their pixels cost the most
#define OCEAN_WAVE_FAR_START 32.0          // distance from the camera where they start thinning out, in tiles
#define OCEAN_WAVE_FAR_END 80.0            // and where they're thinnest
#define OCEAN_WAVE_FAR_KEEP 0.325          // share of waves still spawning fully far away

// How far from a water face's last vertex its pixels can be and still use its list, in wave cells. A little more than a
// tile's diagonal.
#define OCEAN_WAVE_FACE_REACH (1.5 / OCEAN_WAVE_SIZE)

// How far a crest sweeps downwind at most, in wave radii
const float oceanWaveMaxSweep = 1.7;
// The share of a wave's life its crest travels for
const float oceanWaveBreakEnd = 0.65;
// The furthest the ragged edges can move the foam, in wave radii, from the sizes of their four noises
const float oceanWaveMaxRagged = 0.5 * (0.3 + 0.18 + 0.14 + 0.1) * OCEAN_WAVE_RAGGEDNESS;
// How ragged the front is, relative to the rest
const float oceanWaveFrontRagged = 0.3;
// How much sooner or later foam fades out than its neighbours, as a share of its fading time
const float foamBreakupSpread = min(0.55 * OCEAN_WAVE_RAGGEDNESS, 0.8);

struct OceanWave {
    vec2 center;      // in wave cells
    vec2 downwind;    // the way it travels, unit length
    float spawnTime;  // in seconds
    float life;       // from 0 to 1
    vec4 r;           // random numbers of its own
    vec4 shape;       // and more
    float radius;     // in wave cells
    float sweep;      // how far its crest travels in all, in radii
    float travelled;  // and how far it has so far
    float trail;      // length of the trail behind the crest, in radii
    float fadeTime;   // how long its foam takes to fade, as a share of its life
    float u;          // how far downwind of its center the spot is, in stretched radii
    float v;          // and how far along its crest, from -1 to 1, one side longer
    float behind;     // how far behind the crest the spot is, in radii
    float thickness;  // how far behind the crest the trail reaches at the spot
    float raggedness; // how ragged the foam's edges are, from 0 to 1
};

// Whether a cell has a wave whose foam may reach a spot, and if so, the wave. Each grid cell has a wave some of the
// time. Each crest sweeps downwind, leaving a trail that breaks up from the back.
//  q:      the spot, in wave cells
//  margin: how far around the spot to check, in wave cells, or 0 for only the spot itself
bool oceanWaveNear(vec2 cell, vec2 q, float time, float margin, out OceanWave wave) {
    const float maxSweep = oceanWaveMaxSweep;
    const float breakEnd = oceanWaveBreakEnd;
    // In wave cells
    const float maxReach = 1.8;
    // Leeway for the checks around a spot, so that rounding can never rule out a wave its pixels would find
    float leeway = margin > 0.0 ? 0.02 : 0.0;
    vec2 h = hash22(cell + 0.31);
    // One chance per lifetime, using the roughness at spawn so existing waves don't pop as it changes
    float cycle = time / OCEAN_WAVE_LIFETIME + h.x;
    float n = floor(cycle);
    // Cheapest checks first, against the roughest water any wave still going could have spawned in, before
    // reading the roughness this one spawned with. More waves the rougher the water.
    float spawnHash = hash12(cell * 5.3 + n * vec2(2.71, 6.13));
    if (spawnHash >= clamp(OCEAN_WAVE_AMOUNT * uboGlobal.waveMaxRoughness, 0.0, 1.0) + leeway)
        return false;
    // Centers lie near the middle of each cell, so neighbours rarely overlap. The grid doesn't show, since the waves
    // vary in size, direction and timing.
    wave.center = cell + 0.4 + 0.2 * hash22(cell * 1.7 + n * vec2(7.31, 3.17));
    vec2 offset = q - wave.center;
    // Below, a wave is skipped beyond maxSweep stretched radii downwind and 1.6 crest half-lengths across, with its
    // radius at most that roughness allows. So nothing further than this from its center can be part of it.
    float maxRadius = mix(0.13, 0.42, h.y) * sqrt(uboGlobal.waveMaxRoughness);
    float reach = min(maxReach,
        length(vec2(OCEAN_WAVE_STRETCH * maxSweep * maxRadius, 1.6 * min(maxRadius * 4.0, 0.9)))) + margin;
    if (dot(offset, offset) > reach * reach)
        return false;
    wave.spawnTime = (n - h.x) * OCEAN_WAVE_LIFETIME;
    // Every second's the same unless the wind or roughness changed recently, so read the first, which is cheaper
    int spawnSecond = int(mod(floor(wave.spawnTime), float(WIND_HISTORY_LENGTH)));
    int historySecond = uboGlobal.waveHistorySteady != 0 ? 0 : spawnSecond;
    float spawnRoughness = uboGlobal.roughnessHistory[historySecond >> 2][historySecond & 3];
    float chance = clamp(OCEAN_WAVE_AMOUNT * spawnRoughness, 0.0, 1.0);
    // Fewer far from where the camera was when the wave spawned, so moving it never changes a wave already going.
    // Not where the waves repeat in every region, since the same wave is then at many distances. Only waves the
    // distance could decide need the camera: under the share kept they spawn anywhere, and at the full chance nowhere.
    if (uboGlobal.waveRegionRepeat == 0 && spawnHash >= chance * OCEAN_WAVE_FAR_KEEP + leeway &&
        spawnHash < chance + leeway) {
        vec4 cameraPair = uboGlobal.waveCameraHistory[spawnSecond >> 1];
        vec2 camera = (spawnSecond & 1) == 0 ? cameraPair.xy : cameraPair.zw;
        float cameraDistance = length(cell + 0.5 - camera) * OCEAN_WAVE_SIZE;
        float farness = smoothstep(OCEAN_WAVE_FAR_START, OCEAN_WAVE_FAR_END, cameraDistance);
        chance *= mix(1.0, OCEAN_WAVE_FAR_KEEP, farness);
    }
    if (spawnHash >= chance + leeway)
        return false;
    wave.r = hash42(cell * 2.9 + n * vec2(1.73, 9.21));
    wave.life = fract(cycle) * (1.0 + wave.r.x * OCEAN_WAVE_RATE_RANDOMNESS * 1.5);
    if (wave.life >= 1.0 + leeway)
        return false;
    wave.shape = hash42(cell * 4.13 + n * vec2(5.31, 2.87));
    wave.sweep = maxSweep * mix(0.6, 1.0, wave.shape.x);
    // Faster waves fade sooner, so their trails don't stretch too far
    wave.fadeTime = mix(0.3, 0.55, wave.r.w) * (maxSweep * 0.6 / wave.sweep);
    // Foam fades from when the crest passed it, the slowest of it over this long. The crest passes nothing once it
    // stops, so some waves are gone before the end of their lives.
    float fadedBefore = wave.life - wave.fadeTime * (1.0 + 0.5 * foamBreakupSpread);
    if (fadedBefore >= breakEnd + leeway)
        return false;
    // Keep the wind from spawn, so wind changes only affect new waves
    float windAngle = uboGlobal.windHistory[historySecond >> 2][historySecond & 3];
    float radius = mix(0.13, 0.42, h.y);
    float angle = windAngle + (wave.r.y - 0.5) * (PI / 7.0);
    wave.downwind = vec2(cos(angle), sin(angle));
    // Long crests are slimmer, to fit the neighbouring cells
    float crestLength = mix(2.4, 4.0, wave.r.z);
    // Bigger in rougher water, while still fitting the neighbouring cells
    wave.radius = min(radius * sqrt(spawnRoughness), 0.9 / crestLength);
    // Downwind in radii, stretched along the way it travels, which is near enough the wind, and along the crest
    // from -1 to 1, one side longer
    wave.u = dot(offset, wave.downwind) / (wave.radius * OCEAN_WAVE_STRETCH);
    float along = dot(offset, vec2(-wave.downwind.y, wave.downwind.x)) / (wave.radius * crestLength);
    float longSide = mix(0.6, 1.0, wave.shape.z);
    float shortSide = mix(1.0, 0.6, wave.shape.z);
    wave.v = along / (along > 0.0 ? longSide : shortSide);
    // The furthest either way within the margin
    float uMargin = margin / (wave.radius * OCEAN_WAVE_STRETCH);
    float vMargin = margin / (wave.radius * crestLength);
    float vLow = along - vMargin;
    vLow /= vLow > 0.0 ? longSide : shortSide;
    float vHigh = along + vMargin;
    vHigh /= vHigh > 0.0 ? longSide : shortSide;
    if (abs(wave.u) - uMargin > maxSweep + leeway || vLow > 1.6 + leeway || vHigh < -1.6 - leeway)
        return false;

    // The crest eases up to speed and back to a stop, its ends lagging so it curves
    wave.travelled = smoothstep(0.0, 1.0, clamp(wave.life / breakEnd, 0.0, 1.0)) * wave.sweep;
    float crest = wave.travelled - 0.5 * wave.sweep;
    float curve = mix(0.05, 0.7, wave.shape.y);
    // Wiggles along the front at three scales
    const vec3 wiggleSizes = vec3(0.16, 0.09, 0.05);
    vec3 wiggleRates = mix(vec3(2.0, 5.0, 11.0), vec3(4.0, 9.0, 17.0), vec3(wave.shape.w, wave.r.y, wave.shape.x));
    vec3 wiggles = sin(wave.v * wiggleRates + vec3(wave.r.y, wave.shape.w, wave.r.x) * TAU);
    float waver = dot(wiggles, wiggleSizes) * OCEAN_WAVE_WIGGLE;
    // A sharp front, and a trail as long as the crest has travelled, fanning out as it spreads
    wave.trail = wave.travelled + 0.05;
    wave.behind = crest - (wave.u + curve * wave.v * wave.v + waver);
    // Thinner towards the ends
    wave.thickness = wave.trail * (1.0 - 0.75 * clamp(wave.v * wave.v, 0.0, 1.0));
    // Less ragged while the trail is short
    wave.raggedness = clamp(wave.trail * 1.5, 0.0, 1.0);
    // The least and most behind the crest within the margin, and the thickest the trail gets
    float leastBehind = wave.behind;
    float mostBehind = wave.behind;
    float thickest = wave.thickness;
    if (margin > 0.0) {
        float nearest = vLow * vHigh <= 0.0 ? 0.0 : min(vLow * vLow, vHigh * vHigh);
        float furthest = max(vLow * vLow, vHigh * vHigh);
        float maxWaver = (wiggleSizes.x + wiggleSizes.y + wiggleSizes.z) * abs(OCEAN_WAVE_WIGGLE);
        leastBehind = crest - (wave.u + uMargin + curve * furthest + maxWaver);
        mostBehind = crest - (wave.u - uMargin + curve * nearest - maxWaver);
        thickest = wave.trail * (1.0 - 0.75 * clamp(nearest, 0.0, 1.0));
    }
    // Skip spots out of reach even with the ragged edges, before working out the noise
    float ragged = oceanWaveMaxRagged * wave.raggedness;
    if (mostBehind + oceanWaveFrontRagged * ragged + leeway <= -0.06 || leastBehind - ragged - leeway >= thickest)
        return false;
    // And spots the crest passed so long ago that their foam has faded, from how far it had travelled by then
    float fadedTravel = smoothstep(0.0, 1.0, clamp(fadedBefore / breakEnd, 0.0, 1.0)) * wave.sweep;
    if (fadedBefore > 0.0 && leastBehind - ragged - leeway >= wave.travelled - fadedTravel)
        return false;
    return true;
}

// A spot's position in wave cells, drifting with the wind and scrolling with the waves
//  tiles: position in world tiles
vec2 oceanWaveCellPosition(vec2 tiles) {
    // Positions within the region rather than the world, where the environment repeats the waves in every region
    vec2 regionOffset = uboGlobal.waveRegionRepeat != 0 ? floor(tiles / 64.0) * 64.0 : vec2(0.0);
    vec2 drifted = tiles - regionOffset - uboGlobal.windDrift * OCEAN_FOAM_DRIFT_SPEED - uboGlobal.waveScroll;
    return drifted / OCEAN_WAVE_SIZE;
}

// Whether waves can form anywhere on a water face, from its vertices' shore data. Its pixels blend their room for
// waves from these, so with none of them over SHORE_WAVE_MIN_ROOM, no pixel is.
bool oceanWaveFaceHasRoom(ivec3 alphaBiasHsl) {
    ivec3 enclosure = alphaBiasHsl >> WATER_ENCLOSURE_SHIFT & WATER_ENCLOSURE_STEPS;
    // From this much enclosure on, as SceneContext stores it, the room is at most SHORE_WAVE_MIN_ROOM
    const int noRoom = int(ceil((1.0 - SHORE_WAVE_MIN_ROOM / SHORE_DISTANCE_MAX) * WATER_ENCLOSURE_STEPS));
    return any(lessThan(enclosure, ivec3(noRoom)));
}

// The waves that may reach anywhere within OCEAN_WAVE_FACE_REACH of a spot, as a bit for each of the 5x5 cells around
// its own, row by row from the lowest
//  q: the spot, in wave cells
int oceanWaveList(vec2 q) {
    vec2 cell0 = floor(q);
    int list = 0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            OceanWave wave;
            // A little further than the pixels check, so rounding can't put one outside
            if (oceanWaveNear(cell0 + vec2(x, y), q, uboGlobal.elapsedTime, OCEAN_WAVE_FACE_REACH * 1.05, wave))
                list |= 1 << ((y + 2) * 5 + x + 2);
        }
    }
    return list;
}
