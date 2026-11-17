/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
#include <uniforms/sky.glsl>
#include <uniforms/materials.glsl>
#include <uniforms/water_types.glsl>

#include <utils/constants.glsl>
#include <utils/lights.glsl>
#include <utils/misc.glsl>
#include <utils/ocean_wave_cells.glsl>

#if WATER_PASS || LEGACY_RENDERER

// Shore foam
#define SHORE_FOAM_STRENGTH 0.31           // peak opacity before lighting
#define FOAM_WATER_BRIGHTNESS 5.0          // all foam is at least this much brighter than the water under it
#define FOAM_LOW_SUN_OPACITY 1.0           // extra foam opacity under a low sun, 1 for double near the horizon
#define OCEAN_FOAM_DEFAULT_COLOR vec3(0.690, 0.643, 0.573) // WaterType's default foam color, whose brightness open water foam has
#define SHORE_FOAM_SOFTNESS 0.3            // width of the eroded edge, in texture space
#define SHORE_FOAM_EDGE_NOISE 0.35         // how far the foam line wanders, in tiles
#define SHORE_FOAM_REACH 0.8               // how far the solid foam reaches between waves, in tiles
#define SHORE_FOAM_CHURN 0.3               // how far the pattern is warped, in texture repeats
#define SHORE_FOAM_SCALE 1.25              // tiles per texture repeat
#define SHORE_FOAM_EVOLVE_PERIOD 2.2       // seconds for each pattern to fade in and out
#define SHORE_FOAM_SWAY 0.08               // how far the foam sways in and out, in tiles
#define SHORE_FOAM_SWAY_REACH 4.0          // distance from the shore the sway has faded out by, in tiles
#define SHORE_FOAM_SWAY_PERIOD 12.0        // seconds per sway, including the lull after it
#define SHORE_FOAM_SWAY_MAX_LULL 0.4       // longest lull, as a fraction of the period
#define SHORE_FOAM_LULL_DRIFT 0.25         // drift during the longest lull, relative to a sway

// Shore waves
#define SHORE_WAVE_INTERVAL 4.5            // average seconds between waves, at least a third of a wave's life
#define SHORE_WAVE_CHANCE 0.55             // how much of the shore each wave covers, before sets and lulls
#define SHORE_WAVE_PIECE_SIZE 1.375        // size of the pieces each wave breaks into along the shore, in tiles
#define SHORE_WAVE_SET_PERIOD 10.0         // seconds per set and lull
#define SHORE_WAVE_SET_CONTRAST 0.8        // how busy sets and quiet lulls are, from 0 to 1
#define SHORE_WAVE_FRONT_BREAKUP 0.12      // how far the texture breaks up the front, in tiles
#define SHORE_WAVE_MIN_STRENGTH 0.6        // weakest wave, relative to the strongest
#define SHORE_WAVE_COVERAGE 1.5            // coverage boost before capping, so most waves are solid
#define SHORE_WAVE_REACH 2.25              // how far out waves form, in tiles. At most about 3.6.
#define SHORE_WAVE_END_SPEED 0.7           // how much they slow towards the shore, as their speed there relative to where they form
#define SHORE_WAVE_TRAVEL_TIME 6.5         // seconds a wave takes to ease to a stop
#define SHORE_WAVE_FADE_TIME 1.4           // seconds a wave's foam takes to break up where it was laid
#define SHORE_WAVE_MIN_STOP 0.6            // shortest way in a wave gets before stopping, as a share of the reach
#define SHORE_WAVE_MAX_STOP 0.85           // and the furthest, short of the shore
#define SHORE_WAVE_FULL_OPACITY 0.3        // distance from the shore of full opacity, in tiles
#define SHORE_WAVE_OPACITY_CURVE 1.5       // how late waves fade in, 1 for linear
#define SHORE_WAVE_MIN_ROOM 5.0            // open water needed nearby for waves, in tiles
#define SHORE_WAVE_FULL_ROOM 6.0           // and for full strength, up to SHORE_DISTANCE_MAX

// Open water foam, shared by the lace and ocean waves. OCEAN_FOAM_DRIFT_SPEED is in ocean_wave_cells.glsl.
#define OCEAN_FOAM_TEXTURE_SCALE 2.5       // tiles per speck texture repeat
#define OCEAN_FOAM_SHORE_GAP 1.8           // clear water along the shore for the ocean waves, in tiles
#define OCEAN_FOAM_SHORE_FADE 3.9          // distance of full strength from the shore, in tiles. At most 12.

// Lace
#define OCEAN_LACE_STRENGTH 0.108          // opacity before lighting
#define OCEAN_LACE_SHORE_GAP 1.05          // clear water along the shore, in tiles
#define OCEAN_LACE_SHORE_FADE 4.05         // distance of full strength from the shore, in tiles. At most 12.
#define OCEAN_LACE_MIN_ROOM 1.0            // open water needed nearby for lace, in tiles, like SHORE_WAVE_MIN_ROOM
#define OCEAN_LACE_FULL_ROOM 4.0           // and for full strength
#define OCEAN_LACE_SOFTNESS 0.45           // speck softness, in texture space
#define OCEAN_LACE_SCALE 1.6               // cell size, in tiles
#define OCEAN_LACE_LINE_WIDTH 0.22         // fraction of a cell
#define OCEAN_LACE_WIDTH_VARIATION 0.6     // fraction either way
#define OCEAN_LACE_GAPS 0.4                // how much of the lines break off, from 0 to 1
#define OCEAN_LACE_STRETCH 1.5             // along the wind, 1 for none
#define OCEAN_LACE_SWIRL 1.0               // how much the lines bend, in cells
#define OCEAN_LACE_CURL 0.85               // smaller bends, relative to the swirl
#define OCEAN_LACE_SWIRL_SPEED 2.0         // how fast the swirls shift
#define OCEAN_LACE_CHURN_SPEED 0.3         // how fast the cells change shape
#define OCEAN_LACE_DISTANCE 70.0           // draw distance from the camera, in tiles, matching the default detail distance
#define OCEAN_LACE_COVERAGE 0.72           // speck density in the lines, from 0 to 1
#define OCEAN_LACE_DENSE_SIZE 8.0          // size of denser areas, in tiles
#define OCEAN_LACE_DENSE_AMOUNT 0.5        // how much of the water they cover, from 0 to 1
#define OCEAN_LACE_DENSE_COVERAGE 0.9      // speck density there, from 0 to 1
#define OCEAN_LACE_DENSE_SPEED 0.04        // how fast they come and go

// Ocean waves, breaking out at sea. The settings for where and when they form are in ocean_wave_cells.glsl.
#define OCEAN_WAVE_STRENGTH 0.13           // opacity before lighting
#define OCEAN_WAVE_FRONT_BRIGHTNESS 1.45   // brightness at the front, relative to the rest
#define OCEAN_WAVE_EDGE_BRIGHTNESS 0.4     // extra brightness right at the crest's edge, on top of the front
#define OCEAN_WAVE_EDGE_WIDTH 0.03         // distance behind the crest of half edge brightness, as a share of the wave
#define OCEAN_WAVE_SOFTNESS 0.45           // speck softness, in texture space
#define OCEAN_WAVE_COVERAGE 0.85           // speck density, from 0 to 1
#define OCEAN_WAVE_TAIL_NOISE 0.8          // how much the trails break into streaks, from 0 to 1

// Boat wakes. BoatWakes.java repeats the settings that decide how far the foam reaches, so change those there too.
#define BOAT_WAKE_STRENGTH 1.5             // opacity, relative to the ocean waves' OCEAN_WAVE_STRENGTH
#define BOAT_WAKE_COVERAGE 0.75            // speck density, from 0 to 1
#define BOAT_WAKE_LIFETIME 2.0             // seconds behind the stern until fully faded, within the recorded path
#define BOAT_WAKE_WIDTH 0.35               // width of the foam at the bow, in tiles
#define BOAT_WAKE_ANGLE 7.0                // angle the foam pulls away from the hull at, in degrees
#define BOAT_WAKE_HULL_SPREAD 0.08         // how much wider the foam gets per tile back along the hull
#define BOAT_WAKE_DRIFT 0.6                // how fast the foam drifts outwards behind the stern, in tiles per second
#define BOAT_WAKE_SPREAD 0.5               // how much wider the foam gets per second behind the stern
#define BOAT_WAKE_RAGGEDNESS 0.35          // how ragged the foam's edges get, in tiles, growing as it spreads
#define BOAT_WAKE_END_SOFTNESS 1.5         // how far the end of the tail fades over, in tiles
#define BOAT_WAKE_END_RAGGEDNESS 2.0       // how ragged the end of the tail is, in tiles
#define BOAT_WAKE_END_ALPHA_START 0.6      // life at which the tail starts turning see-through, from 0 to 1
#define BOAT_WAKE_END_BREAKUP 0.8          // how patchy the fade at the end of the tail is
#define BOAT_WAKE_RIM_BRIGHTNESS 1.4       // how much brighter the rim is than the rest of the wake
#define BOAT_WAKE_RIM_BLEND 0.45           // how far the rim blends inwards, as a share of the arms' half-width
#define BOAT_WAKE_RIM_WIDTH 0.3            // denser foam along the arms' outer edge, as a share of their half-width
#define BOAT_WAKE_INNER_REACH 2.0          // how much further each band reaches in towards the boat than out
#define BOAT_WASH_OFFSET 1.5               // how far behind the stern the wash starts, in tiles
#define BOAT_WASH_TAPER 0.0                // life at which the wash starts narrowing to a point, from 0 to 1
#define BOAT_WASH_WIDTH 3.0                // width of the wash where it starts, in tiles
#define BOAT_WASH_SPREAD 0.5               // how much wider the wash behind the stern gets per second
#define BOAT_WASH_LIFETIME 3.0             // seconds until the wash behind the stern fades
#define BOAT_WASH_LENGTH 9.0               // minimum length of the wash, in tiles, for slow boats
#define BOAT_WAKE_STOP_TIME 2.5            // seconds the wake takes to break up once the boat slows
#define BOAT_WAKE_BREAKUP_SCALE 16.0       // how much finer the wake breaks up than the ocean waves' foam
#define BOAT_WAKE_START_TIME 1.5           // seconds a new wake takes to fade in once the boat moves off
#define BOAT_WAKE_STOP_AGEING 0.5          // how fast the foam keeps ageing once the boat stops
#define BOAT_WAKE_STOP_ALPHA_UNEVENNESS 1.0 // how unevenly the wake turns see-through as it disappears, 0 for all at once
#define BOAT_WAKE_STOP_ALPHA_SCALE 4.0     // how much smaller the see-through patches are than the ocean waves' foam
#define BOAT_WAKE_BREAKUP_SOFTNESS 0.8     // how gradually each patch fades as the wake breaks up, the waves' is 0.4
#define BOAT_WAKE_WAVE_MERGE 0.5           // how much of an ocean wave's foam adds to a wake's arm it runs into
#define BOAT_WAKE_SWEPT_RANGE 0.5          // how much further than the nearest path point another can sweep from, in tiles
// The hull outline, tuned for a sloop of the reference size, and scaled to fit each boat
#define BOAT_REFERENCE_LENGTH 10.0         // in tiles
#define BOAT_REFERENCE_WIDTH 3.0           // in tiles
#define BOAT_WAKE_SIZE_FORWARD 0.1         // how far forward the whole wake moves per tile a boat is shorter than that
#define BOAT_HULL_LENGTH 5.0               // in tiles
#define BOAT_WAKE_FORWARD 0.25             // how far forward of the hull the wake's arms start, in tiles
// From the stern to where the arms start
#define BOAT_WAKE_BOW_LENGTH (BOAT_HULL_LENGTH + BOAT_WAKE_FORWARD)
#define BOAT_HULL_WIDTH 2.4                // in tiles
#define BOAT_HULL_BOW_LENGTH 2.0           // how far back from the tip the bow widens to full width, in tiles
// The big NPC ships, relative to other boats
#define BOAT_SHIP_FORWARD -0.25            // wake offset, in tiles
#define BOAT_SHIP_ANGLE 0.3                // arm angle and outward drift
#define BOAT_SHIP_LIFETIME 1.8             // arm lifetime
#define BOAT_SHIP_WASH_LENGTH 1.5          // wash length
#define BOAT_SHIP_WASH_WIDTH 0.85          // wash width
#define BOAT_SHIP_WASH_OFFSET 0.72         // extra wash offset, in reference tiles

#if DAYLIGHT_CYCLE
    #include <utils/sky_sampling.glsl>
#endif

// Shore waves slow from SHORE_WAVE_REACH in to the shore, down to this fraction of their starting speed
const float shoreWaveReach = float(SHORE_WAVE_REACH);
const float shoreWaveEndSpeed = clamp(float(SHORE_WAVE_END_SPEED), 0.05, 1.0);
// waveTravelDistance out at the reach, where the waves form, worked out once when compiling
const float shoreWaveStartTravel = shoreWaveEndSpeed >= 0.999 ? shoreWaveReach :
    shoreWaveReach / max(1.0 - shoreWaveEndSpeed, 1e-3) * log(1.0 / shoreWaveEndSpeed);

// Distance in steady travel units. Speed falls linearly from SHORE_WAVE_REACH to the shore, so this is the integral of
// its inverse, a logarithm.
float waveTravelDistance(float shoreDist) {
    const float reach = shoreWaveReach;
    const float endSpeed = shoreWaveEndSpeed;
    if (endSpeed >= 0.999)
        return shoreDist;
    // Full speed beyond the reach, and stopping short of zero speed inside the shore
    float d = clamp(shoreDist, -0.9 * endSpeed / (1.0 - endSpeed) * reach, reach);
    float travel = reach / (1.0 - endSpeed) * log((endSpeed + (1.0 - endSpeed) * d / reach) / endSpeed);
    return travel + max(shoreDist - reach, 0.0);
}

// Two channels of value noise for the cost of one
vec2 noise2(vec2 st) {
    vec2 i = floor(st);
    vec2 f = fract(st);
    vec2 a = hash22(i);
    vec2 b = hash22(i + vec2(1, 0));
    vec2 c = hash22(i + vec2(0, 1));
    vec2 d = hash22(i + vec2(1, 1));
    vec2 u = smoothstep(0.0, 1.0, f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Distance to the nearest edge of a churning Voronoi pattern, as the difference between the two nearest points,
// measured stretched along the wind
//  wind: unit length
float cellEdges(vec2 p, float time, vec2 wind) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 crossWind = vec2(-wind.y, wind.x);
    // Squared distances, rooted only for the two nearest
    float nearest = 64.0;
    float second = 64.0;
    // Only the 2x2 block of cells nearest the pixel, rather than all 3x3. Now and then the second nearest point lies
    // outside it, cutting a line short, but that doesn't show through the specks, and it's a quarter cheaper.
    vec2 lo = vec2(f.x < 0.5 ? -1.0 : 0.0, f.y < 0.5 ? -1.0 : 0.0);
    for (int y = 0; y <= 1; y++) {
        for (int x = 0; x <= 1; x++) {
            vec2 offset = lo + vec2(x, y);
            vec2 h = hash22(i + offset);
            vec2 point = offset + 0.5 + 0.4 * sin(time + h * TAU);
            // Squashed along the wind, which stretches the cells along it. Only local offsets are stretched, since
            // rotating the whole pattern with the wind would swing distant parts of it around wildly.
            vec2 toPoint = point - f;
            toPoint = vec2(dot(toPoint, wind) / OCEAN_LACE_STRETCH, dot(toPoint, crossWind));
            float d = dot(toPoint, toPoint);
            second = d < nearest ? nearest : min(second, d);
            nearest = min(nearest, d);
        }
    }
    return sqrt(second) - sqrt(nearest);
}

// Noise that decides which foam fades out first, from 0 to 1, shared by the ocean waves and boat wakes
//  q: position in ocean wave cells
float foamBreakupNoise(vec2 q, float time) {
    return noise(q * 3.7 + time * vec2(-0.09, -0.05) + 21.7);
}

// How much life a patch of foam has left as it fades out unevenly, from 1 down, shared by the ocean waves and boat wakes
//  foamLife: from 0 when fresh to 1 when it's due to be gone
//  breakup: from foamBreakupNoise
float foamLifeLeft(float foamLife, float breakup) {
    return 1.0 - foamLife + (breakup - 0.5) * foamBreakupSpread;
}

// How much of a patch of foam is left as it fades out unevenly, from 0 to 1
float foamLasting(float foamLife, float breakup) {
    return smoothstep(0.0, 0.4, foamLifeLeft(foamLife, breakup));
}

// Foam fading out over the end of its life, from 1 to 0, shared by the ocean waves and boat wakes
float foamEndFade(float life) {
    return 1.0 - smoothstep(0.85, 1.0, life);
}

// Coverage of waves coming in to the shore, from 0 to 1. Like the ocean waves, each rushes in, eases to a stop short of
// the shore, and leaves its foam to break up where it was laid. Rather than searching for each wave, they come in on a
// few steady cycles, each out of step along the shore in its own way, and each wave covers its own pieces of shore with
// its own strength and reach, more of it during sets.
//  shorePoint: nearest point on the shore, in world tiles
//  world:      position, in world tiles
//  foamMask:   foam texture, breaking up the fronts
//  room:       room for waves, from 0 to 1
float shoreWaves(float shoreDist, vec2 shorePoint, vec2 world, float foamMask, float room) {
    float time = uboGlobal.elapsedTime;
    // How far in from where the waves form, in steady travel units, which already slow towards the shore
    float inward = shoreWaveStartTravel - waveTravelDistance(shoreDist) - (foamMask - 0.5) * SHORE_WAVE_FRONT_BREAKUP;
    if (inward <= 0.0)
        return 0.0;

    // Which foam goes first as it breaks up, finer than the ocean waves', worked out once a wave needs it
    bool breakupReady = false;
    float breakup = 0.0;
    float wave = 0.0;
    // Three trains of waves, each arriving every third interval and out of step along the shore in its own way, so
    // neighbouring waves don't come in together. Each wave is over before its train's next arrives.
    for (int k = 0; k < 3; k++) {
        float lag = noise(shorePoint * 0.11 + 3.7 + float(k) * vec2(17.3, 9.1)) * 1.2;
        float cycle = time / (3.0 * SHORE_WAVE_INTERVAL) - lag;
        // Numbered across all the trains, so each wave has its own pieces
        float n = floor(cycle) * 3.0 + float(k);
        float age = fract(cycle) * 3.0 * SHORE_WAVE_INTERVAL;
        // Cheapest checks first, before the noise: whether the wave's over, whether even one going all the way in
        // could have got here yet, and whether even one stopping soonest has foam here that could still be left
        float furthestStop = shoreWaveStartTravel * SHORE_WAVE_MAX_STOP;
        if (age >= SHORE_WAVE_TRAVEL_TIME + SHORE_WAVE_FADE_TIME * 1.5 || inward >= furthestStop)
            continue;
        if (age <= SHORE_WAVE_TRAVEL_TIME * (1.0 - sqrt(1.0 - inward / furthestStop)))
            continue;
        float nearestStop = shoreWaveStartTravel * SHORE_WAVE_MIN_STOP;
        if (inward < nearestStop && age - SHORE_WAVE_TRAVEL_TIME * (1.0 - sqrt(1.0 - inward / nearestStop)) >=
            SHORE_WAVE_FADE_TIME * (1.0 + 0.5 * foamBreakupSpread))
            continue;
        // Its own stretches of shore, strength and reach
        vec2 r = noise2(shorePoint / SHORE_WAVE_PIECE_SIZE + n * vec2(7.31, 3.17));
        // Sets and lulls, rolling along the coast together
        float sets = noise(vec2(n * SHORE_WAVE_INTERVAL / SHORE_WAVE_SET_PERIOD, dot(shorePoint, vec2(0.037, 0.021))));
        // Capped, so even during sets the waves break into pieces rather than running along the whole shore
        float spawnChance = clamp(SHORE_WAVE_CHANCE * (1.0 + (sets - 0.5) * 2.0 * SHORE_WAVE_SET_CONTRAST), 0.0, 0.65);
        // Value noise is rarely near 0 or 1, so the chance is spread over its middle
        float present = smoothstep(1.0 - spawnChance - 0.08, 1.0 - spawnChance + 0.08, (r.x - 0.5) * 1.6 + 0.5);
        if (present <= 0.0)
            continue;
        // Where it stops, from part way in to the shore, changing only gradually along it so the front stays smooth
        float stopNoise = noise(shorePoint * 0.2 + n * vec2(3.91, 8.27));
        float stop = shoreWaveStartTravel *
            mix(SHORE_WAVE_MIN_STOP, SHORE_WAVE_MAX_STOP, saturate((stopNoise - 0.2) * 1.6));
        if (inward >= stop)
            continue;
        // Easing to a stop, as front = stop * (1 - (1 - t)^2), so when the front passed here
        float passed = SHORE_WAVE_TRAVEL_TIME * (1.0 - sqrt(1.0 - inward / stop));
        float foamAge = age - passed;
        if (foamAge <= 0.0)
            continue;
        if (!breakupReady) {
            breakupReady = true;
            breakup = foamBreakupNoise(world / 2.5, time);
        }
        // Breaking up where it was laid, like the ocean waves' foam, with the whole wave gone by the end of its life
        float lasting = foamLasting(foamAge / SHORE_WAVE_FADE_TIME, breakup) *
            foamEndFade(age / (SHORE_WAVE_TRAVEL_TIME + SHORE_WAVE_FADE_TIME * 1.5));
        float front = smoothstep(0.0, 0.15, foamAge);
        float strength = mix(SHORE_WAVE_MIN_STRENGTH, 1.0, r.y) * present;
        // Capped after the boost, so strong waves are solid
        wave = max(wave, front * lasting * saturate(strength * room * SHORE_WAVE_COVERAGE));
    }
    return wave;
}

// Whether the vertex shader lists the waves near each water face, as only the zone renderer's does
#define OCEAN_WAVE_FACE_LIST (OCEAN_WAVES && ZONE_RENDERER && WATER_PASS)
#if OCEAN_WAVE_FACE_LIST
    // Position of the face's last vertex in wave cells, and the waves near it, from oceanWaveList
    flat in vec2 fOceanWaveOrigin;
    flat in int fOceanWaveList;
#endif

// Ocean wave coverage and foam age, from 0 to 1. Each crest sweeps downwind, leaving a trail that breaks up from the
// back. Each grid cell has a wave some of the time.
//  q:            position in wave cells
//  shoreDist:    distance to the shore, in tiles
//  towardsShore: direction to the shore, or 0 where unknown
//  swept:        the most a boat has swept over here, from 0 to 1, and the latest time it did that much, then how
//                much the last boat to sweep over here did, and when
vec2 oceanWaveCells(vec2 q, float time, float shoreDist, vec2 towardsShore, vec4 swept) {
    // Shared noise, each worked out once a wave needs it
    bool raggedReady = false;
    bool breakupReady = false;
    float ragged = 0.0;
    float breakup = 0.0;
    vec2 cell0 = floor(q);
    float result = 0.0;
    float age = 0.0;
    // Which of the 3x3 cells around this one may have a wave reaching here, as a bit each, row by row from the lowest
    int nearby = 0x1FF;
    #if OCEAN_WAVE_FACE_LIST
        // Most water is clear of every wave, and the vertex shader has already found the waves near this face, so only
        // those cells are searched. The list holds wherever is near its vertex, in the waves' own space.
        vec2 fromVertex = q - fOceanWaveOrigin;
        if (dot(fromVertex, fromVertex) <= OCEAN_WAVE_FACE_REACH * OCEAN_WAVE_FACE_REACH) {
            // Its 5x5 cells always hold these 3x3, being within a cell of each other
            ivec2 first = ivec2(cell0) - ivec2(floor(fOceanWaveOrigin)) + 1;
            int rows = fOceanWaveList >> (first.y * 5 + first.x);
            nearby = (rows & 7) | ((rows >> 5) & 7) << 3 | ((rows >> 10) & 7) << 6;
            if (nearby == 0)
                return vec2(0.0);
        }
    #endif
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if ((nearby >> ((y + 1) * 3 + x + 1) & 1) == 0)
                continue;
            OceanWave wave;
            if (!oceanWaveNear(cell0 + vec2(x, y), q, time, 0.0, wave))
                continue;
            // Waves a boat swept over stay broken up for the rest of their lives
            float unswept = 1.0 - max(wave.spawnTime < swept.y ? swept.x : 0.0, wave.spawnTime < swept.w ? swept.z : 0.0);
            if (unswept <= 0.0)
                continue;
            float life = wave.life;
            float sweep = wave.sweep;
            float travelled = wave.travelled;
            float trail = wave.trail;
            float u = wave.u;
            float v = wave.v;
            float behind = wave.behind;
            float thickness = wave.thickness;
            float raggedness = wave.raggedness;

            if (!raggedReady) {
                raggedReady = true;
                // From broad lumps to fine tears. oceanWaveMaxRagged must cover these.
                ragged = (noise(q * 2.2 + time * vec2(0.05, -0.04)) - 0.5) * 0.3;
                ragged += (noise(q * 5.1 + time * vec2(-0.07, 0.06) + 9.3) - 0.5) * 0.18;
                ragged += (noise(q * 11.3 + time * vec2(0.09, 0.08) + 4.1) - 0.5) * 0.14;
                ragged += (noise(q * 23.7 + time * vec2(-0.12, 0.1) + 17.9) - 0.5) * 0.1;
                ragged *= OCEAN_WAVE_RAGGEDNESS;
            }
            // Less ragged at the front
            behind += ragged * mix(oceanWaveFrontRagged, 1.0, saturate(behind / trail)) * raggedness;
            float foamAge = saturate(behind / trail);
            float ends = 1.0 - smoothstep(0.5, 1.0, abs(v) + ragged * 0.8 - foamAge * 0.3);
            float cover = ends * (1.0 - smoothstep(0.0, 0.06, -behind)) * (1.0 - smoothstep(0.7, 1.0, behind / thickness));
            // The rest only takes foam away
            if (cover <= 0.0)
                continue;
            if (!breakupReady) {
                breakupReady = true;
                breakup = foamBreakupNoise(q, time);
            }

            // Foam fades from when the crest passed it, found by inverting the crest's smoothstep
            float passedTravel = saturate((travelled - max(behind, 0.0)) / sweep);
            float passed = (0.5 - sin(asin(1.0 - 2.0 * passedTravel) / 3.0)) * oceanWaveBreakEnd;
            float foamLife = max(life - passed, 0.0);
            // The longest it can last is worked into oceanWaveNear, so change that with this
            float lasting = foamLifeLeft(foamLife / wave.fadeTime, breakup);
            cover *= smoothstep(0.0, 0.4, lasting);
            // The trail breaks into streaks, more so further behind the front
            if (cover > 0.0) {
                float streaks = noise(vec2(u * 1.6, v * 7.0) + wave.r.zw * 50.0);
                streaks = smoothstep(0.25, 0.7, streaks);
                cover *= mix(1.0, streaks, saturate(behind * 1.5) * OCEAN_WAVE_TAIL_NOISE);
            }
            // Fade in and out over its life
            float fade = smoothstep(0.0, 0.2, life) * foamEndFade(life);

            // Skip waves that would start or end too near the shore, estimating its distance at each end
            if (shoreDist < SHORE_DISTANCE_MAX - 0.3) {
                // The crest's path, in tiles
                vec2 path = wave.downwind * (sweep * wave.radius * OCEAN_WAVE_STRETCH);
                vec2 startOffset = (wave.center - q - 0.5 * path) * OCEAN_WAVE_SIZE;
                vec2 endOffset = startOffset + path * OCEAN_WAVE_SIZE;
                float pathShoreDist = shoreDist - max(dot(startOffset, towardsShore), dot(endOffset, towardsShore));
                fade *= smoothstep(OCEAN_FOAM_SHORE_GAP, OCEAN_FOAM_SHORE_FADE, pathShoreDist);
            }
            cover *= fade * unswept;
            if (cover > result) {
                result = cover;
                // Freshest at the front
                age = mix(saturate(1.0 - lasting), life, 0.5) * smoothstep(0.0, 0.5, max(behind, 0.0));
            }
        }
    }
    return vec2(result, age);
}

// Lace coverage, from 0 to 1
//  drifted: position in world tiles, drifted with the wind
float oceanLace(vec2 drifted, vec2 wind, float churnTime) {
    float time = uboGlobal.elapsedTime;
    vec2 p = drifted / OCEAN_LACE_SCALE;

    // Fade out with horizontal distance from the camera, up to the detail draw distance. Ignoring height keeps
    // looking straight down from pushing the lace out of range.
    float laceDistance = length(IN.position.xz - uboGlobal.cameraPos.xz) / 128.0;
    float maxDistance = min(float(OCEAN_LACE_DISTANCE), uboGlobal.detailDrawDistance);
    float laceFade = 1.0 - smoothstep(max(maxDistance * 0.5, maxDistance - 10.0), maxDistance, laceDistance);
    if (laceFade <= 0.0)
        return 0.0;

    // Bend and curl the straight cell edges
    float swirlTime = churnTime * OCEAN_LACE_SWIRL_SPEED;
    vec2 swirl = noise2(p * 1.3 + swirlTime * vec2(0.02, -0.015));
    vec2 curl = noise2(p * 3.3 + swirlTime * vec2(0.031, 0.024) + 7.1);
    p += ((swirl - 0.5) + (curl - 0.5) * OCEAN_LACE_CURL) * OCEAN_LACE_SWIRL;

    // Width variation, and gaps breaking the lines into strands
    float width = OCEAN_LACE_LINE_WIDTH * (1.0 + (swirl.x - 0.5) * 2.0 * OCEAN_LACE_WIDTH_VARIATION);
    float gapStart = OCEAN_LACE_GAPS * 0.85;
    float gaps = smoothstep(gapStart - 0.12, gapStart + 0.12, noise2(p * 1.8 + time * vec2(-0.023, 0.019) + 13.1).x);

    // Lines, skipped where broken off
    if (gaps <= 0.0)
        return 0.0;
    float lace = (1.0 - smoothstep(0.0, width, cellEdges(p, churnTime * OCEAN_LACE_CHURN_SPEED, wind))) * gaps;
    // Most water is between the lines
    if (lace <= 0.0)
        return 0.0;

    // Denser areas come and go
    float wt = time * OCEAN_LACE_DENSE_SPEED;
    float dense = noise(drifted / OCEAN_LACE_DENSE_SIZE + vec2(wt, -0.7 * wt));
    dense = smoothstep(1.0 - OCEAN_LACE_DENSE_AMOUNT - 0.15, 1.0 - OCEAN_LACE_DENSE_AMOUNT + 0.15, dense);

    lace *= mix(OCEAN_LACE_COVERAGE, OCEAN_LACE_DENSE_COVERAGE, dense) * laceFade;
    return lace;
}

// Ocean wave coverage, from 0 to 1, and brightness relative to OCEAN_WAVE_STRENGTH
//  drifted: position in world tiles, drifted with the wind
//  swept: the most a boat has swept over here, from 0 to 1, and the latest time it did that much, then how
//  much the last boat to sweep over here did, and when
vec2 oceanWaves(vec2 drifted, float shoreDist, vec2 towardsShore, vec4 swept) {
    vec2 q = drifted / OCEAN_WAVE_SIZE;
    vec2 cells = oceanWaveCells(q, uboGlobal.elapsedTime, shoreDist, towardsShore, swept);
    // Brightest and densest at the front
    float front = 1.0 - smoothstep(0.0, 0.6, cells.y);
    float coverage = cells.x * mix(OCEAN_WAVE_COVERAGE, 1.0, front);
    // With a little extra brightness right at the crest's edge, tailing off softly
    float behind = cells.y / OCEAN_WAVE_EDGE_WIDTH;
    float edge = 1.0 / (1.0 + behind * behind);
    return vec2(coverage, mix(1.0, OCEAN_WAVE_FRONT_BRIGHTNESS, front) + OCEAN_WAVE_EDGE_BRIGHTNESS * edge);
}

// Noise shared by every boat wake at a spot, each pair worked out lazily
struct WakeNoise {
    bool foamReady;
    float breakup; // which foam fades out first, as foamBreakupNoise, finer than the ocean waves'
    bool fadeReady;
    float endRagged; // the tail's ragged end, from 0 to 1
    float stopBreakup; // which foam turns see-through first once the boat stops, as foamBreakupNoise
};

// Foam from the arms or wash, whichever has more here: x foam, y life, z rim, w churned (which hides ocean waves and
// lace), each from 0 to 1. The arms peel off the bow at an angle, then drift outwards and spread behind the stern.
//  world: position in world tiles
//  side: distance from the hull's centre line, or the path behind the stern
//  fromBow: distance back from the bow, in tiles
//  age: seconds since the stern passed, 0 alongside the hull
//  arms: false for just the wash, for a boat going backwards
//  squareBow: for a boat with a square front, like a raft, whose foam starts at full width
//  outward: the way the foam drifts here, in world tiles per tile of side
vec4 boatWakeFoam(vec2 world, float side, float fromBow, float age, bool arms, bool squareBow, bool bigShip,
    vec2 outward, inout WakeNoise wakeNoise) {
    float armAngle = bigShip ? BOAT_SHIP_ANGLE : 1.0;
    float armLifetime = bigShip ? BOAT_SHIP_LIFETIME : 1.0;
    float washLength = bigShip ? BOAT_SHIP_WASH_LENGTH : 1.0;
    float washScale = bigShip ? BOAT_SHIP_WASH_WIDTH : 1.0;
    float washOffset = bigShip ? BOAT_SHIP_WASH_OFFSET : 0.0;

    float washStart = BOAT_WAKE_BOW_LENGTH + BOAT_WASH_OFFSET + washOffset;
    float angleSlope = tan(radians(BOAT_WAKE_ANGLE)) * armAngle;
    float life = arms ? age / (BOAT_WAKE_LIFETIME * armLifetime) : 1.0;
    // Whichever is later, so the wash still shows behind a slow boat
    float washLife = min(age / BOAT_WASH_LIFETIME, (fromBow - washStart) / BOAT_WASH_LENGTH) / washLength;
    if (life >= 1.0 && washLife >= 1.0)
        return vec4(0.0);

    float alongHull = min(fromBow, BOAT_HULL_LENGTH);
    float hullEdge = BOAT_HULL_WIDTH * 0.5 * (squareBow ? 1.0 : sqrt(saturate(fromBow / BOAT_HULL_BOW_LENGTH)));
    float foamLine = hullEdge + alongHull * angleSlope + age * BOAT_WAKE_DRIFT * armAngle;
    float spread = BOAT_WAKE_WIDTH + alongHull * BOAT_WAKE_HULL_SPREAD + age * BOAT_WAKE_SPREAD;
    float raggedness = BOAT_WAKE_RAGGEDNESS * (0.3 + alongHull * 0.15 + age);
    // Narrowing to a point towards its end
    float washWidth = (BOAT_WASH_WIDTH * 0.5 + age * BOAT_WASH_SPREAD) * washScale *
        (1.0 - smoothstep(min(BOAT_WASH_TAPER, 0.999), 1.0, washLife)) + 1e-4;
    // Skip the noise beyond the furthest reach
    if (side >= max(life < 1.0 ? foamLine + spread * 0.5 : 0.0, washWidth) + raggedness * 0.5)
        return vec4(0.0);
    if (!wakeNoise.foamReady) {
        wakeNoise.foamReady = true;
        wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
    }
    float breakup = wakeNoise.breakup;
    // Ragged edges carried outwards with the foam, rather than the edge sliding across them
    float ragged = (noise((world - outward * foamLine) * 1.3) - 0.5) * raggedness;
    // Across each band, from 0 in its middle to 1 at its edge
    float offset = side - foamLine + ragged;
    float across = abs(offset) / (spread * 0.5 * (offset < 0.0 ? BOAT_WAKE_INNER_REACH : 1.0));
    // A sharp outer edge, with a denser rim like the ocean waves' fronts
    float foam = 1.0 - smoothstep(offset > 0.0 ? 1.0 - BOAT_WAKE_RIM_WIDTH * 0.5 : 0.6, 1.0, across);
    const float rimStart = 1.0 - BOAT_WAKE_RIM_WIDTH;
    float rim = offset > 0.0 ? smoothstep(rimStart - max(BOAT_WAKE_RIM_BLEND, 1e-3), rimStart, across) : 0.0;
    float armsLasting = foamLasting(life, breakup) * foamEndFade(life);
    foam *= armsLasting;

    float washAcross = (side + ragged) / washWidth;
    float wash = (1.0 - smoothstep(0.5, 1.0, washAcross)) *
        smoothstep(washStart - 0.5, washStart + 0.5, fromBow);
    wash *= foamLasting(washLife, breakup) * foamEndFade(washLife);

    // Inside the arms out to their middle, and under the wash, fading with their foam
    float churned = max((1.0 - smoothstep(-0.25, 0.25, offset / (spread * 0.5))) * armsLasting, wash);

    return foam >= wash ? vec4(foam, life, rim, churned) : vec4(wash, washLife, 0.0, churned);
}

// How much a wake has swept over a spot, from 0 to 1, even after its foam is gone. Matches boatWakeFoam's shape.
//  breakup: as boatWakeFoam's, so it stops growing where the foam has broken up
float boatWakeSwept(float side, float fromBow, float age, bool arms, bool squareBow, bool bigShip, float breakup) {
    float hullEdge = BOAT_HULL_WIDTH * 0.5 * (squareBow ? 1.0 : sqrt(saturate(fromBow / BOAT_HULL_BOW_LENGTH)));
    // Life at which the foam here is half faded, as foamLasting
    float goneLife = 0.8 + (breakup - 0.5) * foamBreakupSpread;
    float edge;
    if (arms) {
        float armLifetime = bigShip ? BOAT_SHIP_LIFETIME : 1.0;
        edge = hullEdge + (min(fromBow, BOAT_HULL_LENGTH) * tan(radians(BOAT_WAKE_ANGLE)) +
            min(age, BOAT_WAKE_LIFETIME * armLifetime * goneLife) * BOAT_WAKE_DRIFT) * (bigShip ? BOAT_SHIP_ANGLE : 1.0);
    } else {
        age = min(age, BOAT_WASH_LIFETIME * (bigShip ? BOAT_SHIP_WASH_LENGTH : 1.0) * goneLife);
        edge = max(hullEdge, (BOAT_WASH_WIDTH * 0.5 + age * BOAT_WASH_SPREAD) * (bigShip ? BOAT_SHIP_WASH_WIDTH : 1.0));
    }
    return 1.0 - smoothstep(edge - 0.4, edge, side);
}

// Track the strongest sweep and its latest time in xy, and the latest sweep and its amount in zw
void addSwept(inout vec4 swept, float amount, float time) {
    if (amount > swept.x || (amount == swept.x && time > swept.y))
        swept.xy = vec2(amount, time);
    if (time > swept.w || (time == swept.w && amount > swept.z))
        swept.zw = vec2(amount, time);
}

// Boat wake coverage, opacity, rim and churn, each from 0 to 1. Each path runs from the bow to the stern as it is now,
// so the foam hugs the hull through turns, then back along where the stern went.
//  p: position in tiles relative to the scene
//  swept: as addSwept, from every path segment rather than just the nearest, so it doesn't jump on turns
vec4 boatWakes(vec2 p, out vec4 swept) {
    swept = vec4(0.0, -1e9, 0.0, -1e9);
    // In world tiles, so the noise doesn't jump when the scene's base moves at loading lines
    vec2 world = p + vec2(uboGlobal.sceneBase) / 128.0;
    vec3 wake = vec3(0.0);
    float churned = 0.0;
    WakeNoise wakeNoise = WakeNoise(false, 0.0, false, 0.0, 0.0);
    // Nearly all water is outside every wake's bounds, so find which wakes may reach here first, as a bit each. A loop
    // of its own this small is much cheaper to get through than one holding all the work below.
    int inside = 0;
    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        // Slots are filled in order
        if (uboGlobal.boatWakeHulls[boat].x < 1.0)
            break;
        vec4 bounds = uboGlobal.boatWakeBounds[boat];
        if (all(greaterThanEqual(p, bounds.xy)) && all(lessThanEqual(p, bounds.zw)))
            inside |= 1 << boat;
    }
    for (int boat = 0; inside != 0; boat++, inside >>= 1) {
        if ((inside & 1) == 0)
            continue;
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);

        vec4 size = uboGlobal.boatWakeSizes[boat];
        bool squareBow = size.z > 0.5;
        bool bigShip = size.w > 0.5;
        float lengthScale = size.x > 0.0 ? size.x / BOAT_REFERENCE_LENGTH : 1.0;
        float sideScale = size.y > 0.0 ? size.y / BOAT_REFERENCE_WIDTH : 1.0;
        float bowLength = BOAT_WAKE_BOW_LENGTH * lengthScale;

        vec4 headingStern = uboGlobal.boatWakeHeadings[boat];
        // Shorter boats' wakes sit further forward, except rafts', which are centred
        float forward = squareBow ? 0.0 : (BOAT_REFERENCE_LENGTH - size.x) * BOAT_WAKE_SIZE_FORWARD;
        if (bigShip)
            forward += BOAT_SHIP_FORWARD;
        vec2 sternPos = headingStern.zw + headingStern.xy * forward;
        vec2 bowPos = sternPos + headingStern.xy * bowLength;
        int first = boat * BOAT_WAKE_MAX_POINTS;
        bool arms = hull.w < 0.5;
        // So the waves it broke come back gently rather than popping when the wake is no longer sent
        float sweptFade = (1.0 - smoothstep(0.0, 1.0, saturate(hull.y / BOAT_WAKE_STOP_TIME))) *
            saturate(hull.z / BOAT_WAKE_START_TIME);

        // The nearest point on the path. Points hold position, age and distance back from the bow.
        float nearest = 1e9;
        // And the next nearest part of it, from another segment
        float second = 1e9;
        vec2 nearestOffset = vec2(0.0);
        float fromBow = 0.0;
        float age = 0.0;
        bool outside = false;
        bool firstSegment = true;
        vec4 stern = vec4(sternPos, 0.0, bowLength);
        vec4 a = vec4(bowPos, 0.0, 0.0);
        // From the bow to the stern, then on to each point of the path
        for (int i = 0; i <= count; i++) {
            vec4 b = stern;
            if (i > 0) {
                b = uboGlobal.boatWakePoints[first + i - 1];
                b.w += bowLength;
            }
            vec2 segment = b.xy - a.xy;
            float length2 = dot(segment, segment);
            if (length2 >= 1e-6) {
                float t = saturate(dot(p - a.xy, segment) / length2);
                vec2 offset = p - (a.xy + segment * t);
                float dist2 = dot(offset, offset);
                // In front of the bow, or past the end of the path
                bool beyond = (firstSegment && t <= 0.0) || (i == count && t >= 1.0);
                if (dist2 < nearest) {
                    second = nearest;
                    nearest = dist2;
                    nearestOffset = offset;
                    fromBow = mix(a.w, b.w, t);
                    age = mix(a.z, b.z, t);
                    outside = beyond;
                } else {
                    second = min(second, dist2);
                }
                firstSegment = false;
            }
            a = b;
        }

        // Only parts of the path about as near as the nearest, which the arms are drawn from, so the cut doesn't run
        // ahead of them. Faded over BOAT_WAKE_SWEPT_RANGE, so it doesn't jump on turns.
        float side = sqrt(nearest);
        float armReach = BOAT_HULL_WIDTH * 0.5 + (BOAT_HULL_LENGTH * tan(radians(BOAT_WAKE_ANGLE)) +
            BOAT_WAKE_LIFETIME * (bigShip ? BOAT_SHIP_LIFETIME : 1.0) * BOAT_WAKE_DRIFT) * (bigShip ? BOAT_SHIP_ANGLE : 1.0);
        if (sweptFade > 0.0 && (!arms || side / sideScale < armReach)) {
            if (!wakeNoise.foamReady) {
                wakeNoise.foamReady = true;
                wakeNoise.breakup = foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
            }
            if (sqrt(second) - side >= BOAT_WAKE_SWEPT_RANGE) {
                // No other segment is near enough to add anything, as along any straight stretch, so there's no need
                // to go through them all again
                if (!outside) {
                    float amount = boatWakeSwept(side / sideScale, fromBow / lengthScale,
                        age + hull.y * BOAT_WAKE_STOP_AGEING, arms, squareBow, bigShip, wakeNoise.breakup) * sweptFade;
                    if (amount > 0.0)
                        addSwept(swept, amount, uboGlobal.elapsedTime - age - hull.y);
                }
            } else {
                firstSegment = true;
                a = vec4(bowPos, 0.0, 0.0);
                for (int i = 0; i <= count; i++) {
                    vec4 b = stern;
                    if (i > 0) {
                        b = uboGlobal.boatWakePoints[first + i - 1];
                        b.w += bowLength;
                    }
                    vec2 segment = b.xy - a.xy;
                    float length2 = dot(segment, segment);
                    if (length2 >= 1e-6) {
                        float t = saturate(dot(p - a.xy, segment) / length2);
                        float dist = length(p - (a.xy + segment * t));
                        bool beyond = (firstSegment && t <= 0.0) || (i == count && t >= 1.0);
                        float near = 1.0 - smoothstep(0.0, BOAT_WAKE_SWEPT_RANGE, dist - side);
                        if (!beyond && near > 0.0) {
                            // Ages are as of when the boat stopped, so add the time since
                            float segmentAge = mix(a.z, b.z, t);
                            float amount = boatWakeSwept(dist / sideScale, mix(a.w, b.w, t) / lengthScale,
                                segmentAge + hull.y * BOAT_WAKE_STOP_AGEING, arms, squareBow, bigShip, wakeNoise.breakup) *
                                sweptFade * near;
                            if (amount > 0.0)
                                addSwept(swept, amount, uboGlobal.elapsedTime - segmentAge - hull.y);
                        }
                        firstSegment = false;
                    }
                    a = b;
                }
            }
        }
        if (outside)
            continue;

        // Scaled to the reference boat. The time since stopping is added more slowly, so the tail doesn't wipe away
        // towards the boat too fast.
        vec2 outward = nearestOffset / max(side, 1e-4) * sideScale;
        vec4 foamLife = boatWakeFoam(world, side / sideScale, fromBow / lengthScale,
            age + hull.y * BOAT_WAKE_STOP_AGEING, arms, squareBow, bigShip, outward, wakeNoise);
        float foam = foamLife.x;
        float fadeIn = saturate(hull.z / BOAT_WAKE_START_TIME);
        float fadeOut = saturate(hull.y / BOAT_WAKE_STOP_TIME);
        churned = max(churned, foamLife.w * fadeIn * (1.0 - fadeOut));
        // The fades only take foam away, so skip them where another wake already has more
        if (foam <= wake.x)
            continue;
        if (!wakeNoise.fadeReady) {
            wakeNoise.fadeReady = true;
            wakeNoise.endRagged = noise(world * 1.3 + 7.1);
            wakeNoise.stopBreakup =
                foamBreakupNoise(world * BOAT_WAKE_STOP_ALPHA_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime);
        }
        float breakup = wakeNoise.breakup;
        // Fade out raggedly towards the end of the tail, rather than cutting straight across
        float endEdge = a.w - fromBow - wakeNoise.endRagged * BOAT_WAKE_END_RAGGEDNESS;
        foam *= smoothstep(0.0, 1.0, endEdge / BOAT_WAKE_END_SOFTNESS - breakup * BOAT_WAKE_END_BREAKUP);
        // Break up once the boat stops, like the waves' foam but softer, starting where the most ragged parts go
        if (fadeOut > 0.0) {
            float start = 1.0 - BOAT_WAKE_BREAKUP_SOFTNESS - 0.5 * foamBreakupSpread;
            float life = mix(start, 1.0, fadeOut);
            foam *= smoothstep(0.0, BOAT_WAKE_BREAKUP_SOFTNESS, foamLifeLeft(life, breakup)) * foamEndFade(life);
        }
        // Fade in after moving off, and out unevenly after stopping
        const float unevenness = BOAT_WAKE_STOP_ALPHA_UNEVENNESS;
        float alpha = fadeIn * (1.0 - saturate(fadeOut * (1.0 + unevenness) - wakeNoise.stopBreakup * unevenness));
        alpha *= 1.0 - smoothstep(BOAT_WAKE_END_ALPHA_START, 1.0, foamLife.y);
        if (foam > wake.x)
            wake = vec3(foam, alpha, foamLife.z);
    }
    return vec4(wake, churned);
}

// Foam built from texture specks, thinning with the coverage and fading at its edges
float oceanSpecks(float coverage, float softness, float specks) {
    return smoothstep(1.0 - coverage, 1.0 - coverage + softness, specks) * smoothstep(0.0, 0.7, coverage);
}

vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);

    // All the foam is worked out here, before the water's normals and lighting, rather than where it's used. Whatever
    // has been worked out already and is needed again later has to be kept through the foam's loops and noise, which
    // slows the whole shader down, so as little as possible should come before it.

    // Boat wakes first, since they break up the ocean waves
    vec4 wake = vec4(0.0);
    vec4 swept = vec4(0.0, -1e9, 0.0, -1e9);
    if (bool(BOAT_WAKES) && waterType.hasFoam != 0)
        wake = boatWakes(IN.position.xz / 128.0, swept);

    // Unless the water type stays still, like water carried along inside a boat in a scrolling scene
    vec2 waveScroll = waterType.noScroll != 0 ? vec2(0.0) : uboGlobal.waveScroll;
    vec2 flowMapUv = worldUvs(15) + waveScroll / 15.0 + animationFrame(50 * waterType.duration);
    float flowMapStrength = 0.025;

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;

    // The foam's texture wherever there's foam, for shading it
    float foamMask = 0;
    // Distance to land per vertex. See SceneContext for the shore data layout.
    ivec3 shoreDistances = fAlphaBiasHsl & SHORE_DISTANCE_STEPS;
    float foamAmount = 0;
    // Skip faces neither near the shore nor on open water. Uniform per face, so texture derivatives stay valid. Cast,
    // since max is ambiguous with whole number settings.
    const float foamReach = max(float(SHORE_WAVE_REACH), float(SHORE_FOAM_REACH));
    const int foamReachSteps = int(ceil((foamReach + SHORE_FOAM_EDGE_NOISE) / SHORE_DISTANCE_MAX * SHORE_DISTANCE_STEPS));
    ivec3 enclosureSteps = fAlphaBiasHsl >> WATER_ENCLOSURE_SHIFT & WATER_ENCLOSURE_STEPS;
    // Whichever of the lace and waves needs the least room
    const float openWaterRoom = bool(OCEAN_LACE) ?
        min(float(OCEAN_LACE_MIN_ROOM), float(SHORE_WAVE_MIN_ROOM)) : float(SHORE_WAVE_MIN_ROOM);
    const int openWaterSteps = int(ceil((1.0 - openWaterRoom / SHORE_DISTANCE_MAX) * WATER_ENCLOSURE_STEPS));
    bool nearShore = bool(SHORE_FOAM) && any(lessThan(shoreDistances, ivec3(foamReachSteps)));
    bool openWater = (bool(OCEAN_LACE) || bool(OCEAN_WAVES)) && waterType.noWaves == 0 &&
        any(lessThan(enclosureSteps, ivec3(openWaterSteps)));
    // Rougher water, from the wind strength, has more and stronger waves, more visible lace and faster churn. The
    // waves are stronger in proportion to it.
    float roughness = uboGlobal.waterRoughness;
    float waveStrength = OCEAN_WAVE_STRENGTH * roughness;
    // Foam specks for the ocean waves, lace and boat wakes, drifting with the wind and warped by the flow map.
    // Derivatives taken here, where they're valid.
    vec2 speckUv = worldUvs(OCEAN_FOAM_TEXTURE_SCALE) +
        uboGlobal.windDrift * (OCEAN_FOAM_DRIFT_SPEED / OCEAN_FOAM_TEXTURE_SCALE) + uvFlow * flowMapStrength;
    vec2 speckUvDx = dFdx(speckUv);
    vec2 speckUvDy = dFdy(speckUv);
    // The wakes' own foam, worked out before the lace and waves so that less has to be kept through them. Its specks
    // are negative where there's no wake.
    float wakeAmount = 0.0;
    float wakeSpecks = -1.0;
    float churned = wake.w;
    if (wake.x > 0.0) {
        wakeSpecks = smoothstep(0.1, 0.65,
            textureGrad(textureArray, vec3(speckUv, MAT_WATER_FOAM.colorMap), speckUvDx, speckUvDy).r);
        // As soft as the ocean waves' specks, and denser along the rim like their fronts
        wakeAmount = oceanSpecks(wake.x * mix(BOAT_WAKE_COVERAGE, 1.0, wake.z), OCEAN_WAVE_SOFTNESS, wakeSpecks);
        float rimBrightness = mix(1.0, BOAT_WAKE_RIM_BRIGHTNESS, wake.z);
        wakeAmount *= wake.y * waveStrength * BOAT_WAKE_STRENGTH * rimBrightness;
    }
    float waveAmount = 0.0;
    // Lace, ocean waves and boat wakes, kept apart from the shore foam for their color
    float oceanAmount = 0.0;
    if (waterType.hasFoam != 0 && (nearShore || openWater)) {
        float shoreDist = dot(fTexBlend, vec3(shoreDistances)) / SHORE_DISTANCE_STEPS * SHORE_DISTANCE_MAX;
        // Blended corners read 0 across one tile channels, so measure to land within square tiles instead.
        // WATER_SQUARE_TILE_BIT on the second vertex marks a square tile, and on the first the sw triangle.
        if (nearShore && (fAlphaBiasHsl[1] & WATER_SQUARE_TILE_BIT) != 0 && any(equal(shoreDistances, ivec3(0)))) {
            bool swTriangle = (fAlphaBiasHsl[0] & WATER_SQUARE_TILE_BIT) != 0;
            // From (0, 0) at sw to (1, 1) at ne
            vec2 local = swTriangle ?
                vec2(fTexBlend.y, fTexBlend.z) :
                vec2(fTexBlend.x + fTexBlend.z, fTexBlend.x + fTexBlend.y);
            vec2 corners[3] = vec2[](vec2(0, 0), vec2(1, 0), vec2(0, 1));
            if (!swTriangle)
                corners = vec2[](vec2(1, 1), vec2(0, 1), vec2(1, 0));
            shoreDist = SHORE_DISTANCE_MAX;
            for (int i = 0; i < 3; i++)
                if (shoreDistances[i] == 0)
                    shoreDist = min(shoreDist, distance(local, corners[i]));
            // Sides bordering land: west, east, south, north
            int landSides = fAlphaBiasHsl[0] >> SHORE_SIDES_SHIFT & 15;
            if ((landSides & 1) != 0)
                shoreDist = min(shoreDist, local.x);
            if ((landSides & 2) != 0)
                shoreDist = min(shoreDist, 1.0 - local.x);
            if ((landSides & 4) != 0)
                shoreDist = min(shoreDist, local.y);
            if ((landSides & 8) != 0)
                shoreDist = min(shoreDist, 1.0 - local.y);
        }
        // Hide tile edges in the foam line
        if (nearShore) {
            float edgeNoise = noise(worldUvs(0.4) + uboGlobal.elapsedTime * vec2(0.03, -0.02));
            shoreDist += (edgeNoise - 0.5) * SHORE_FOAM_EDGE_NOISE;
        }

        // Direction to land, where known, for the shore foam and ocean waves. Blending vectors avoids wrapping, and
        // weakens where vertices disagree.
        vec2 shoreDir = vec2(0);
        vec2 towardsShore = vec2(0);
        if ((nearShore || bool(OCEAN_WAVES)) && any(lessThan(shoreDistances, ivec3(SHORE_DISTANCE_STEPS)))) {
            vec3 shoreAngles = vec3(fAlphaBiasHsl >> SHORE_DIRECTION_SHIFT & SHORE_DIRECTION_STEPS - 1) *
                (TAU / SHORE_DIRECTION_STEPS);
            shoreDir = vec2(dot(fTexBlend, cos(shoreAngles)), dot(fTexBlend, sin(shoreAngles)));
            towardsShore = length(shoreDir) > 0.1 ? normalize(shoreDir) : vec2(0);
        }
        // No waves in small ponds or narrow channels
        float waterRoom = (1.0 - dot(fTexBlend, vec3(enclosureSteps)) / WATER_ENCLOSURE_STEPS) * SHORE_DISTANCE_MAX;
        float roomForWaves = smoothstep(SHORE_WAVE_MIN_ROOM, SHORE_WAVE_FULL_ROOM, waterRoom);

        // Open water foam specks, drifting with the wind. Sampled before per-pixel branches, for valid derivatives.
        float oceanMask = 0.0;
        // Positions within the region rather than the world, where the environment repeats the waves in every region
        vec2 regionOffset = uboGlobal.waveRegionRepeat != 0 ? floor(-worldUvs(64.0)) * 64.0 : vec2(0.0);
        if (openWater) {
            // Scrolled with the waves and lace, unlike the boat wakes' specks
            vec2 oceanSpeckUv = speckUv + (waveScroll + regionOffset) / OCEAN_FOAM_TEXTURE_SCALE;
            oceanMask = smoothstep(0.1, 0.65, texture(textureArray, vec3(oceanSpeckUv, MAT_WATER_FOAM.colorMap)).r);
            foamMask = oceanMask;
        }

        // Shore foam and waves
        if (nearShore) {
            // Churning in place
            vec2 foamWarp = noise2(worldUvs(1.5) + uboGlobal.elapsedTime * vec2(0.07, 0.03)) - 0.5;
            foamWarp = foamWarp * SHORE_FOAM_CHURN + uvFlow * flowMapStrength;
            vec2 foamUv = worldUvs(SHORE_FOAM_SCALE) + foamWarp;

            // Sway in and out, with the same timing everywhere. Each cycle is a sway, then a lull.
            float swayCycle = uboGlobal.elapsedTime / SHORE_FOAM_SWAY_PERIOD;
            float swayIndex = floor(swayCycle);
            float swayT = fract(swayCycle);
            float lull = hash11(swayIndex) * SHORE_FOAM_SWAY_MAX_LULL;
            float swayStrength;
            if (swayT < 1.0 - lull) {
                swayT /= 1.0 - lull;
                swayStrength = mix(0.6, 1.0, hash11(swayIndex + 500.0));
            } else {
                // Drift slowly during the lull
                swayT = (swayT - (1.0 - lull)) / lull;
                swayStrength = lull / SHORE_FOAM_SWAY_MAX_LULL * SHORE_FOAM_LULL_DRIFT;
            }
            // In, then out, starting and stopping smoothly
            float sway = sin(swayT * TAU) * sin(swayT * PI) * swayStrength;
            // Towards the shore, since world UVs are negated. Only near it, where the direction means something.
            sway *= 1.0 - smoothstep(foamReach, SHORE_FOAM_SWAY_REACH, shoreDist);
            foamUv += shoreDir * (sway * SHORE_FOAM_SWAY / SHORE_FOAM_SCALE);

            // Three copies a third of a period apart, each fading in and out with a sin² weight and jumping while
            // invisible. The weights sum to 1.5 and their squares to 9/8, keeping brightness and contrast steady.
            float evolveCycle = uboGlobal.elapsedTime / SHORE_FOAM_EVOLVE_PERIOD;
            float foamSum = 0;
            for (int i = 0; i < 3; i++) {
                float copyCycle = evolveCycle + i / 3.0;
                float weight = sin(fract(copyCycle) * PI);
                vec2 offset = hash21(floor(copyCycle) + i * 1000.0) * 4.0;
                foamSum += weight * weight * texture(textureArray, vec3(foamUv + offset, MAT_WATER_FOAM.colorMap)).r;
            }
            // Restore the contrast blending flattens
            const float foamAverage = 0.58;
            float foamTex = (foamSum / 1.5 - foamAverage) * (1.5 / sqrt(9.0 / 8.0)) + foamAverage;
            // The texture mostly ranges from 0.4 to 0.7
            foamMask = smoothstep(0.1, 0.65, foamTex);

            // Waves fade in as they come in, over solid foam along the shore
            float waveOpacity =
                saturate((SHORE_WAVE_REACH - shoreDist) / max(SHORE_WAVE_REACH - SHORE_WAVE_FULL_OPACITY, 0.01));
            waveOpacity = pow(waveOpacity, SHORE_WAVE_OPACITY_CURVE);
            float wave = 0.0;
            if (roomForWaves > 0.0 && waveOpacity > 0.0) {
                // Nearest point on the shore, in world tiles, which stays put as waves come in
                vec2 shorePoint = -worldUvs(1.0) + towardsShore * shoreDist;
                wave = shoreWaves(shoreDist, shorePoint, -worldUvs(1.0), foamMask, roomForWaves);
            }
            float shoreBand = 1.0 - smoothstep(0.05, SHORE_FOAM_REACH, shoreDist);

            // Erode the texture, so thin foam breaks into patches
            float foamCoverage = max(shoreBand, wave);
            foamAmount = smoothstep(1.0 - foamCoverage, 1.0 - foamCoverage + SHORE_FOAM_SOFTNESS, foamMask);
            // The shore band keeps its strength only where it dominates
            float bandDominance = smoothstep(-0.15, 0.15, shoreBand - wave);
            foamAmount *= SHORE_FOAM_STRENGTH * mix(waveOpacity, max(waveOpacity, shoreBand), bandDominance);
        }

        // Lace and ocean waves, fading out near the shore
        float oceanRoom = roomForWaves * smoothstep(OCEAN_FOAM_SHORE_GAP, OCEAN_FOAM_SHORE_FADE, shoreDist);
        float laceRoom = smoothstep(OCEAN_LACE_MIN_ROOM, OCEAN_LACE_FULL_ROOM, waterRoom) *
            smoothstep(OCEAN_LACE_SHORE_GAP, OCEAN_LACE_SHORE_FADE, shoreDist);
        if (max(oceanRoom, laceRoom) > 0.0) {
            vec2 drifted = -worldUvs(1.0) - regionOffset - uboGlobal.windDrift * OCEAN_FOAM_DRIFT_SPEED - waveScroll;
            if (bool(OCEAN_LACE) && churned < 1.0) {
                // Stronger in proportion to the roughness, and denser too, up to solid line cores
                float lace = oceanLace(drifted, uboGlobal.windDirection, uboGlobal.waterChurnTime);
                lace = saturate(lace * sqrt(roughness)) * (1.0 - churned);
                // Fading towards the shore in opacity, keeping its specks as dense
                float laceStrength = OCEAN_LACE_STRENGTH * roughness * laceRoom;
                oceanAmount = max(oceanAmount, oceanSpecks(lace, OCEAN_LACE_SOFTNESS, oceanMask) * laceStrength);
            }
            if (bool(OCEAN_WAVES) && churned < 1.0 && oceanRoom > 0.0) {
                vec2 waves = oceanWaves(drifted, shoreDist, towardsShore, swept);
                float amount = oceanSpecks(waves.x * oceanRoom * (1.0 - churned), OCEAN_WAVE_SOFTNESS, oceanMask);
                waveAmount = amount * waveStrength * waves.y;
                oceanAmount = max(oceanAmount, waveAmount);
            }
        }
    }
    if (wakeSpecks >= 0.0) {
        // Where an ocean wave runs into an arm, their foam adds up
        oceanAmount = max(oceanAmount, wakeAmount + waveAmount * BOAT_WAKE_WAVE_MERGE);
        foamMask = max(foamMask, wakeSpecks);
    }
    float shoreAmount = foamAmount;
    foamAmount = max(foamAmount, oceanAmount);
    // The original fade along shore tile edges, when the new shore foam is off
    float originalFoam = 0.0;
    float originalMask = 0.0;
    if (!bool(SHORE_FOAM)) {
        float shoreLineMask = 1 - dot(fTexBlend, vec3(notEqual(shoreDistances, ivec3(0))));
        originalFoam = clamp(pow(1.0 - ((1.0 - min(shoreLineMask, 0.8)) / 0.7), 3), 0.0, 1.0) * waterType.hasFoam;
        vec2 uvFoam = IN.uv + uvFlow * flowMapStrength;
        originalMask = texture(textureArray, vec3(uvFoam, MAT_WATER_FOAM.colorMap)).r;
    }

    // Each layer scrolled along with the ocean waves, on top of its own animation
    vec2 anchorUv1 = worldUvs(3).yx + waveScroll.yx / 3.0;
    vec2 anchorUv2 = worldUvs(5) + waveScroll / 5.0;
    vec2 anchorUv3 = worldUvs(7) + waveScroll / 7.0;
    vec2 uv1 = anchorUv1 - animationFrame(28 * waterType.duration);
    vec2 uv2 = anchorUv2 + animationFrame(24 * waterType.duration);
    vec2 uv3 = anchorUv3 + animationFrame(21 * waterType.duration);

    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;
    // get diffuse textures
    vec3 n1 = linearToSrgb(texture(textureArray, vec3(uv1, waterType.normalMap)).xyz);
    vec3 n2 = linearToSrgb(texture(textureArray, vec3(uv2, waterType.normalMap)).xyz);
    vec3 n3 = linearToSrgb(texture(textureArray, vec3(uv3, waterType.normalMap)).xyz);

    // normals
    n1 = -vec3((n1.x * 2 - 1) * waterType.normalStrength, n1.z, (n1.y * 2 - 1) * waterType.normalStrength);
    n2 = -vec3((n2.x * 2 - 1) * waterType.normalStrength, n2.z, (n2.y * 2 - 1) * waterType.normalStrength);
    n3 = -vec3((n3.x * 2 - 1) * waterType.normalStrength, n3.z, (n3.y * 2 - 1) * waterType.normalStrength);
    vec3 normals = normalize(n1 + n2 + n3);

    float lightDotNormals = dot(normals, uboGlobal.lightDir);
    float downDotNormals = -normals.y;
    float viewDotNormals = dot(viewDir, normals);

    vec3 distortion = mat3(uboGlobal.invLightProjectionMatrix) * vec3(uvFlow * .0015, 0);
    vec3 lightAxis = uboGlobal.invLightProjectionMatrix[2].xyz;
    vec2 surfaceDistortion = vec2(0);
    if (abs(lightAxis.y) > .001 * length(lightAxis))
        surfaceDistortion = distortion.xz - lightAxis.xz * (distortion.y / lightAxis.y);
    vec3 shadowReceiver = IN.position + vec3(surfaceDistortion.x, 0, surfaceDistortion.y);
    float shadow = sampleShadowMap(shadowReceiver, vec3(0, -1, 0), false, false, true);
    float inverseShadow = 1 - shadow;

    vec3 vSpecularStrength = vec3(waterType.specularStrength);
    vec3 vSpecularGloss = vec3(waterType.specularGloss);
    float combinedSpecularStrength = waterType.specularStrength;

    // calculate lighting

    // ambient light
    vec3 ambientLightOut = uboGlobal.ambientColor * uboGlobal.ambientStrength;

    // directional light
    vec3 dirLightColor = uboGlobal.lightColor * uboGlobal.lightStrength;
    vec3 unshadowedLightOut = max(lightDotNormals, 0.0) * dirLightColor;

    // apply shadows
    dirLightColor *= inverseShadow;

    vec3 lightColor = dirLightColor;
    vec3 lightOut = max(lightDotNormals, 0.0) * lightColor;

    // directional light specular
    vec3 lightSpecularOut = vec3(0);
    #if WATER_SPECULAR_REFLECTIONS
        #if DAYLIGHT_CYCLE
        if (!uboSky.enabled)
        #endif
            lightSpecularOut = lightColor * specular(fTexBlend, viewDir, reflect(-uboGlobal.lightDir, normals), vSpecularGloss, vSpecularStrength);
    #endif

    // point lights
    vec3 pointLightsOut = vec3(0);
    vec3 pointLightsSpecularOut = vec3(0);
    calculateLighting(IN.position, normals, viewDir, fTexBlend, vSpecularGloss, vSpecularStrength,
        0.0, pointLightsOut, pointLightsSpecularOut);

    // sky light
    vec3 skyLightColor = uboGlobal.fogColor;
    float skyLightStrength = 0.5;
    float skyDotNormals = downDotNormals;
    vec3 skyLightOut = max(skyDotNormals, 0.0) * skyLightColor * skyLightStrength;


    // lightning
    vec3 lightningColor = vec3(1.0, 1.0, 1.0);
    float lightningStrength = uboGlobal.lightningBrightness;
    float lightningDotNormals = downDotNormals;
    vec3 lightningOut = max(lightningDotNormals, 0.0) * lightningColor * lightningStrength;


    // underglow
    vec3 underglowOut = uboGlobal.underglowColor * max(normals.y, 0) * uboGlobal.underglowStrength;


    // fresnel reflection
    float baseOpacity = 0.4;
    float fresnel = 1.0 - clamp(viewDotNormals, 0.0, 1.0);
    float finalFresnel = clamp(mix(baseOpacity, 1.0, fresnel * 1.2), 0.0, 1.0);
    vec3 surfaceColor = vec3(0);
    #if DAYLIGHT_CYCLE && WATER_SPECULAR_REFLECTIONS
    vec3 celestialReflection = vec3(0);
    #endif
    #if REDUCE_GLARE_INTENSITY
        vec3 surfaceWithoutGlare;
    #endif

    #if DAYLIGHT_CYCLE
    if (uboSky.enabled) {
        vec3 n = normals;
        n.y *= 0.16;
        // Compress exaggerated slopes before they reflect below the water plane.
        // For slope p = n.xz / -n.y, the reflected ray points upward when
        // v * (1 - dot(p, p)) + 2 * dot(p, viewDir.xz) > 0, where v is view elevation.
        // Resolve that slope limit, then approach it smoothly instead of pinning rays
        // to the horizon. This also keeps the normal facing an above-water camera.
        float viewElevation = -viewDir.y;
        if (viewElevation > 0.0) {
            float towardCamera = dot(n.xz, viewDir.xz);
            float slopeLimitRatio = sqrt(towardCamera * towardCamera + viewElevation * viewElevation * dot(n.xz, n.xz)) - towardCamera;
            slopeLimitRatio /= viewElevation * -n.y;
            n.xz *= inversesqrt(1.0 + slopeLimitRatio * slopeLimitRatio);
        } else {
            n = vec3(0, -1, 0);
        }
        n = normalize(n);
        vec3 skyNormal = normalize(normals * vec3(0.2, 1, 0.2));
        vec3 skyViewDir = reflect(-viewDir, skyNormal);
        vec3 diskViewDir = reflect(-viewDir, n);
        // Reduce only nebula emission to suit the deliberately strong legacy reflection.
        const float nebulaReflectionStrength = .25f;
        SkySample sky = sampleSky(
            skyViewDir,
            diskViewDir,
            false,
            false,
            n,
            0.07,
            nebulaReflectionStrength,
            WATER_SPECULAR_REFLECTIONS != 0,
            0
        );

        #if !DECOUPLE_WATER_FROM_SKY_COLOR
            surfaceColor = linearToSrgb(sky.background * finalFresnel);
            #if REDUCE_GLARE_INTENSITY
                surfaceWithoutGlare = linearToSrgb(max(sky.background - sky.glare, vec3(0.0)) * finalFresnel);
            #endif
        #endif

        #if WATER_SPECULAR_REFLECTIONS
            // Only the active source has a shadow map. Do not shadow the other disk
            // with unrelated geometry projected along the active light's direction.
            vec3 sunDir = uboSky.sunDir * vec3(1, -1, 1);
            vec3 moonDir = uboSky.moonDir * vec3(1, -1, 1);
            bool moonOwnsShadowMap = dot(uboGlobal.lightDir, moonDir) > dot(uboGlobal.lightDir, sunDir);
            vec3 shadowedReflection = moonOwnsShadowMap ?
                sky.moon * uboSky.moonReflectionVisibility * uboSky.moonShadowVisibility : sky.sun;
            float reflectionShadow = 0.0;
            if (any(greaterThan(shadowedReflection, vec3(0.0))))
                reflectionShadow = sampleShadowMap(shadowReceiver, vec3(0, -1, 0), false, false, false);
            celestialReflection =
                sky.sun * (moonOwnsShadowMap ? 1.0 : 1.0 - reflectionShadow) +
                sky.moon * uboSky.moonReflectionVisibility *
                    (moonOwnsShadowMap ? 1.0 - reflectionShadow * uboSky.moonShadowVisibility : 1.0);
        #endif
    }
    #if !DECOUPLE_WATER_FROM_SKY_COLOR
        // When not decoupling the water color, only run the old calculation when the sky is disabled
        else
    #endif
    #endif // DAYLIGHT_CYCLE
    if (finalFresnel < 0.5) {
        surfaceColor = mix(uboGlobal.waterColorDark, uboGlobal.waterColorMid, finalFresnel * 2);
    } else {
        surfaceColor = mix(uboGlobal.waterColorMid, uboGlobal.waterColorLight, (finalFresnel - 0.5) * 2);
    }

    #if REDUCE_GLARE_INTENSITY
        #if DAYLIGHT_CYCLE && !DECOUPLE_WATER_FROM_SKY_COLOR
        if (!uboSky.enabled)
        #endif
            surfaceWithoutGlare = surfaceColor;
        vec4 waterWithoutGlare;
    #endif

    vec4 water;
    float opacityFresnel = max(finalFresnel, 1.0 - dot(viewDir, vec3(0, -1, 0)));
    // Extra foam opacity under a low sun, where the sky's reflection would wash it out
    // Open water foam takes only the hue of the water type's foam color, at the default's brightness, since the colors'
    // brightness was tuned for the original shore fade, where it also sets how strong the foam is. Blended by how much of
    // the foam here is open water foam rather than shore foam.
    vec3 oceanFoamColor = OCEAN_FOAM_DEFAULT_COLOR;
    float foamColorLuminance = linearSrgbLuminance(waterType.foamColor);
    if (foamColorLuminance > 1e-3) {
        oceanFoamColor = waterType.foamColor * (linearSrgbLuminance(OCEAN_FOAM_DEFAULT_COLOR) / foamColorLuminance);
        // Strong hues can't reach that brightness without going past white
        oceanFoamColor /= max(1.0, max(oceanFoamColor.r, max(oceanFoamColor.g, oceanFoamColor.b)));
    }
    float oceanShare = oceanAmount / max(oceanAmount + shoreAmount, 1e-4);
    vec3 foamBaseColor = mix(waterType.foamColor, oceanFoamColor, oceanShare);
    float foamLowSun = 0.0;
    if (foamAmount > 0) {
        float sunHeight = -uboGlobal.lightDir.y;
        #if DAYLIGHT_CYCLE
        // The sun itself, since the light switches over to the moon at night
        if (uboSky.enabled)
            sunHeight = uboSky.sunDir.y;
        #endif
        // Easing back off as it sets, when the sky dims too
        foamLowSun = (1.0 - smoothstep(0.0, 0.5, sunHeight)) * smoothstep(0.0, 0.2, sunHeight);
    }

    #if REDUCE_GLARE_INTENSITY
    // With glare reduction enabled, first evaluate the glare-free baseline, then the normal water.
    for (int pass = 0; pass <= REDUCE_GLARE_INTENSITY; pass++)
    #endif
    {
        vec3 surface = surfaceColor;
        vec3 directionalSpecular = lightSpecularOut;
        #if REDUCE_GLARE_INTENSITY
            if (pass == 0) {
                surface = surfaceWithoutGlare;
                directionalSpecular = vec3(0.0);
            }
        #endif

        vec3 surfaceColorOut = surface * max(combinedSpecularStrength, 0.2);


        // apply lighting
        vec3 compositeLight =
            ambientLightOut + lightOut + directionalSpecular + skyLightOut + lightningOut +
            underglowOut + pointLightsOut + pointLightsSpecularOut + surfaceColorOut;

        vec3 baseColor = waterType.surfaceColor * compositeLight;

        // Hacks to improve visibility between different sailing water types, based on unshadowed luminance
        bool isHazardous = waterType.fresnelAmount == 0.86;
        float fresnelAmount = isHazardous ? 0.85 : waterType.fresnelAmount;
        baseColor = mix(baseColor, surface, fresnelAmount);
        if (isHazardous) {
            vec3 unshadowedBaseColor = waterType.surfaceColor *
                (ambientLightOut + unshadowedLightOut + skyLightOut + underglowOut);
            float waterLuminance = linearSrgbLuminance(srgbToLinear(max(unshadowedBaseColor, vec3(0))));
            float skyLuminance = linearSrgbLuminance(srgbToLinear(max(surface, vec3(0))));
            float reflectionDominance = skyLuminance / max(waterLuminance, 1e-6);
            float reflectionAmount = mix(fresnelAmount, fresnelAmount * 0.65, smoothstep(1.0, 4.0, reflectionDominance));
            baseColor += (fresnelAmount - reflectionAmount) * (unshadowedBaseColor - surface);
        }
        if (fresnelAmount == 0.85)
            baseColor *= .75f; // Sailing hack

        vec3 foamColor = vec3(0);
        float litFoamAmount = 0;
        if (foamAmount > 0) {
            vec3 foamAlbedo = foamBaseColor * mix(0.5, 0.7, foamMask);
            foamColor = foamAlbedo * compositeLight;
            // At least FOAM_WATER_BRIGHTNESS times the water's brightness, since at sunrise and sunset the water is
            // lit mostly by the sky's reflection. Tinted by the light, so it isn't grey then.
            float waterBrightness = linearSrgbLuminance(max(baseColor, vec3(0)));
            vec3 lightTint = max(compositeLight, vec3(0));
            lightTint /= max(linearSrgbLuminance(lightTint), 1e-4);
            foamColor = max(foamColor, foamAlbedo / 0.6 * lightTint * min(waterBrightness * FOAM_WATER_BRIGHTNESS, 1.3));
            // The color carries the lighting, so only fade the opacity in real darkness
            float brightness = linearToSrgb(linearSrgbLuminance(srgbToLinear(saturate(foamColor))));
            litFoamAmount = foamAmount * saturate(brightness * 4.0);
            litFoamAmount = saturate(litFoamAmount * (1.0 + FOAM_LOW_SUN_OPACITY * foamLowSun));
        }
        // The original shore fade, where it's stronger
        if (!bool(SHORE_FOAM)) {
            vec3 originalColor = waterType.foamColor * originalMask * compositeLight;
            float originalLit = originalFoam * originalColor.r;
            if (originalLit > litFoamAmount) {
                foamColor = originalColor;
                litFoamAmount = originalLit;
            }
        }
        baseColor = mix(baseColor, foamColor, litFoamAmount);
        vec3 specularComposite = mix(directionalSpecular, vec3(0.0), litFoamAmount);
        baseColor += pointLightsSpecularOut + directionalSpecular / 3;

        float alpha = max(waterType.baseOpacity, max(litFoamAmount, max(opacityFresnel, length(specularComposite / 3))));

        if (waterType.isFlat) {
            baseColor = mix(waterType.depthColor, baseColor, alpha);
            alpha = 1;
        }

        water = vec4(baseColor, alpha);
        #if REDUCE_GLARE_INTENSITY
            if (pass == 0)
                waterWithoutGlare = water;
        #endif
    }

    #if DAYLIGHT_CYCLE && WATER_SPECULAR_REFLECTIONS
        if (uboSky.enabled) {
            float reflectionLuminance = linearSrgbLuminance(celestialReflection);
            if (reflectionLuminance > 0.0) {
                celestialReflection *= 4.4; // looks about right
                // Approximate the reflection's dominance over at most 18% linear-gray
                // underwater light. Bright HDR highlights approach opaque before source-alpha
                // blending; this is an artistic approximation to the actual sRGB composite.
                float reflectionOpacity = reflectionLuminance / (reflectionLuminance + 0.18);
                water.a = mix(water.a, 1.0, reflectionOpacity);
                // Reconstruct approximate linear light, add the shadowed reflection, then tonemap them together.
                vec3 light = inverse_tonemap_hue_preserving(srgbToLinear(water.rgb));
                light += celestialReflection;
                light = tonemap_hue_preserving(light);
                light = softClipColor(light);
                light = linearToSrgb(light);
                water.rgb = light;
            }
        }
    #endif

    #if REDUCE_GLARE_INTENSITY
        water.rgb = linearToSrgb(reduceGlare(srgbToLinear(water.rgb), srgbToLinear(waterWithoutGlare.rgb)));
        // Undo the reflection's opacity boost as it is suppressed, too.
        water.a = mix(waterWithoutGlare.a, water.a, uboGlobal.glareIntensity);
    #endif
    return water;
}

#endif // WATER_PASS || LEGACY_RENDERER

void sampleUnderwater(inout vec3 outputColor, WaterType waterType, float depth, float lightDotNormals) {
    // underwater terrain
    float lowestColorLevel = 500;
    float midColorLevel = 150;
    float surfaceLevel = IN.position.y - depth; // e.g. -1600

    if (depth < midColorLevel) {
        outputColor *= mix(vec3(1), waterType.depthColor, translateRange(0, midColorLevel, depth));
    } else if (depth < lowestColorLevel) {
        outputColor *= mix(waterType.depthColor, vec3(0), translateRange(midColorLevel, lowestColorLevel, depth));
    } else {
        outputColor = vec3(0);
    }

    if (uboGlobal.underwaterCaustics) {
        const float scale = 1.75;
        const float maxCausticsDepth = 128 * 4;

        vec2 causticsUv = worldUvs(scale);

        float depthMultiplier = (IN.position.y - surfaceLevel - maxCausticsDepth) / -maxCausticsDepth;
        depthMultiplier *= depthMultiplier;

        causticsUv *= .75;

        const ivec2 direction = ivec2(1, -2);
        vec2 flow1 = causticsUv + animationFrame(17) * direction;
        vec2 flow2 = causticsUv * 1.5 + animationFrame(23) * -direction;
        vec3 caustics = sampleCaustics(flow1, flow2, .005);

        vec3 causticsColor = uboGlobal.underwaterCausticsColor * uboGlobal.underwaterCausticsStrength;
        outputColor.rgb *= 1 + caustics * causticsColor * depthMultiplier * lightDotNormals * uboGlobal.lightStrength;
    }
}
