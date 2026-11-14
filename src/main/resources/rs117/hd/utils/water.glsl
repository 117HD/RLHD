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

#if WATER_PASS || LEGACY_RENDERER

// Shore foam
#define SHORE_FOAM_STRENGTH 0.31           // peak opacity before lighting
#define FOAM_WATER_BRIGHTNESS 5.0          // all foam is at least this much brighter than the water under it
#define FOAM_LOW_SUN_OPACITY 1.0           // extra foam opacity under a low sun, 1 for double near the horizon
#define SHORE_FOAM_SOFTNESS 0.3            // width of the eroded edge, in texture space
#define SHORE_FOAM_EDGE_NOISE 0.35         // how far the foam line wanders, in tiles
#define SHORE_FOAM_REACH 0.8               // how far the solid foam reaches between waves, in tiles
#define SHORE_FOAM_CHURN 0.3               // how far the pattern is warped, in texture repeats
#define SHORE_FOAM_SCALE 1.25              // tiles per texture repeat
#define SHORE_FOAM_EVOLVE_PERIOD 2.2       // seconds for each pattern to fade in and out
#define SHORE_FOAM_SWAY 0.08               // how far the foam sways in and out, in tiles
#define SHORE_FOAM_SWAY_PERIOD 12.0        // seconds per sway, including the lull after it
#define SHORE_FOAM_SWAY_MAX_LULL 0.4       // longest lull, as a fraction of the period
#define SHORE_FOAM_LULL_DRIFT 0.25         // drift during the longest lull, relative to a sway

// Shore waves
#define SHORE_WAVE_SPACING 2.0             // size of the stretches of shore that each spawn waves, in tiles
#define SHORE_WAVE_INTERVAL 6.0            // average seconds between waves from a stretch
#define SHORE_WAVE_MIN_GAP 5.0             // fewest seconds between waves from a stretch, at most the interval
#define SHORE_WAVE_SET_PERIOD 10.0         // seconds per set and lull
#define SHORE_WAVE_SET_CONTRAST 0.8        // how busy sets and quiet lulls are, from 0 to 1
#define SHORE_WAVE_MIN_LENGTH 1.5          // wave length along the shore, in tiles
#define SHORE_WAVE_MAX_LENGTH 4.0          // at most twice the spacing
#define SHORE_WAVE_END_TAPER 0.3           // fraction of the length fading out at the ends
#define SHORE_WAVE_START_THICKNESS 0.3     // front to back where waves form, in tiles
#define SHORE_WAVE_END_THICKNESS 0.6       // and at the shore
#define SHORE_WAVE_THICKNESS_RANDOMNESS 0.3 // fraction either way
#define SHORE_WAVE_FRONT 0.25              // how gradually the front rises, as a fraction of the thickness
#define SHORE_WAVE_TAIL 0.6                // how gradually the back fades, as a fraction of the thickness
#define SHORE_WAVE_FRONT_BREAKUP 0.12      // how far the texture breaks up the front, in tiles
#define SHORE_WAVE_MIN_STRENGTH 0.6        // weakest wave, relative to the strongest
#define SHORE_WAVE_COVERAGE 1.5            // coverage boost before capping, so most waves are solid
#define SHORE_WAVE_REACH 2.25              // how far out waves form, in tiles. At most about 3.6.
#define SHORE_WAVE_SPEED 0.32              // starting speed, in tiles per second
#define SHORE_WAVE_END_SPEED 0.7           // speed at the shore, as a fraction of the start
#define SHORE_WAVE_SPEED_RANDOMNESS 0.12   // fraction either way
#define SHORE_WAVE_FULL_OPACITY 0.3        // distance from the shore of full opacity, in tiles
#define SHORE_WAVE_OPACITY_CURVE 1.5       // how late waves fade in, 1 for linear
#define SHORE_WAVE_MIN_ROOM 5.0            // open water needed nearby for waves, in tiles
#define SHORE_WAVE_FULL_ROOM 6.0           // and for full strength, up to WATER_ROOM_MAX

// Open water foam, shared by the lace and ocean waves
#define OCEAN_FOAM_DRIFT_SPEED 0.08        // in tiles per second, scaled by the wind speed
#define OCEAN_FOAM_TEXTURE_SCALE 2.5       // tiles per speck texture repeat
#define OCEAN_FOAM_SHORE_GAP 1.8           // clear water along the shore, in tiles
#define OCEAN_FOAM_SHORE_FADE 3.9          // distance of full strength from the shore, in tiles. At most 12.

// Lace
#define OCEAN_LACE_STRENGTH 0.108          // opacity before lighting
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
#define OCEAN_LACE_ROUGHNESS 1.0           // how much rougher water strengthens the lace, as a power of the roughness

// Ocean waves, breaking out at sea
#define OCEAN_WAVE_STRENGTH 0.13           // opacity before lighting
#define OCEAN_WAVE_FRONT_BRIGHTNESS 1.45   // brightness at the front, relative to the rest
#define OCEAN_WAVE_EDGE_BRIGHTNESS 0.4     // extra brightness right at the crest's edge, on top of the front
#define OCEAN_WAVE_EDGE_WIDTH 0.03         // how far back from the crest the extra edge brightness is at half strength, as a share of the wave
#define OCEAN_WAVE_SOFTNESS 0.45           // speck softness, in texture space
#define OCEAN_WAVE_STRETCH 1.5             // along the wind, 1 for none
#define OCEAN_WAVE_SIZE 7.0                // spacing, in tiles
#define OCEAN_WAVE_AMOUNT 0.55             // chance of each spot having a wave each lifetime, from 0 to 1
#define OCEAN_WAVE_LIFETIME 14.0           // longest life, in seconds
#define OCEAN_WAVE_RATE_RANDOMNESS 0.4     // how much faster some waves live, from 0 to 1
#define OCEAN_WAVE_WIGGLE 1.0              // how wiggly the fronts are
#define OCEAN_WAVE_RAGGEDNESS 1.5          // how ragged the edges and breakup are
#define OCEAN_WAVE_COVERAGE 0.85           // speck density, from 0 to 1
#define OCEAN_WAVE_TAIL_NOISE 0.8          // how much the trails break into streaks, from 0 to 1
#define OCEAN_WAVE_ROUGHNESS_AMOUNT 1.0    // how much rougher water adds waves, as a power of the roughness
#define OCEAN_WAVE_ROUGHNESS_STRENGTH 1.0  // how much rougher water strengthens them, as a power of the roughness
#define OCEAN_WAVE_ROUGHNESS_SIZE 0.5      // how much rougher water enlarges them, as a power of the roughness

// Boat wakes. Each boat's hull pushes foam out onto the water as its bow passes, which then drifts outwards, spreads
// and fades where it was laid. Once the boat stops, all of it breaks up.
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
#define BOAT_WAKE_END_ALPHA_START 0.6      // how far through its life the tail's foam starts turning see-through, from 0 to 1
#define BOAT_WAKE_END_BREAKUP 0.8          // how patchy the fade at the end of the tail is
#define BOAT_WAKE_RIM_BRIGHTNESS 1.4       // how much brighter the rim is than the rest of the wake
#define BOAT_WAKE_RIM_BLEND 0.45           // how far the rim blends inwards into the rest of the wake, as a share of the arms' half-width
#define BOAT_WAKE_RIM_WIDTH 0.3            // width of the denser foam along the arms' outer edge, as a share of their half-width
#define BOAT_WAKE_INNER_REACH 2.0          // how much further each band reaches in towards the boat than out
#define BOAT_WASH_OFFSET 1.5               // how far behind the stern the wash starts, in tiles
#define BOAT_WASH_TAPER 0.0                // how far through its life the wash starts narrowing to a point, from 0 to 1
#define BOAT_WASH_WIDTH 3.0                // width of the wash where it starts, in tiles
#define BOAT_WASH_SPREAD 0.5               // how much wider the wash behind the stern gets per second
#define BOAT_WASH_LIFETIME 3.0             // seconds until the wash behind the stern fades
#define BOAT_WASH_LENGTH 9.0               // tiles past its start the wash reaches at least, however slowly the boat goes
#define BOAT_WAKE_STOP_TIME 2.5            // seconds the wake takes to break up once the boat slows
#define BOAT_WAKE_BREAKUP_SCALE 16.0       // how much finer the wake breaks up than the ocean waves' foam
#define BOAT_WAKE_START_TIME 1.5           // seconds a new wake takes to fade in once the boat moves off
#define BOAT_WAKE_STOP_AGEING 0.5          // how fast the foam keeps ageing once the boat stops, which wipes the tail away from its end
#define BOAT_WAKE_STOP_ALPHA_UNEVENNESS 1.0 // how unevenly the wake turns see-through as it disappears, 0 for all at once
#define BOAT_WAKE_STOP_ALPHA_SCALE 4.0     // how much smaller the see-through patches are than the ocean waves' foam
#define BOAT_WAKE_BREAKUP_SOFTNESS 0.8     // how gradually each patch fades as the wake breaks up, the waves' is 0.4
// Each boat's hull, as a simple outline: a pointed bow running into straight sides. These are for a boat this long and
// wide, a medium sloop, and the whole wake is scaled to fit each boat's own size.
#define BOAT_REFERENCE_LENGTH 10.0         // in tiles
#define BOAT_REFERENCE_WIDTH 3.0           // in tiles
#define BOAT_WAKE_SIZE_FORWARD 0.1         // how far forward the whole wake moves per tile a boat is shorter than that
#define BOAT_HULL_LENGTH 5.0               // in tiles
#define BOAT_WAKE_FORWARD 0.25             // how far forward of the hull the wake's arms start, in tiles
// From the stern to where the arms start, which distances along the path are measured back from
#define BOAT_WAKE_BOW_LENGTH (BOAT_HULL_LENGTH + BOAT_WAKE_FORWARD)
#define BOAT_HULL_WIDTH 2.4                // in tiles
#define BOAT_HULL_BOW_LENGTH 2.0           // how far back from the tip the bow widens to full width, in tiles
// The big NPC ships (charter ships, the Rock Island Prison pirate ship and the strange ship) are heavier than other
// boats, so their wakes are shaped and placed by these instead
#define BOAT_SHIP_FORWARD -0.25            // how far forward their whole wake moves, in tiles
#define BOAT_SHIP_ANGLE 0.3                // their arms' angle and outward drift, relative to other boats'
#define BOAT_SHIP_LIFETIME 1.8             // how much longer their arms last than other boats'
#define BOAT_SHIP_WASH_LENGTH 1.5          // how much further their wash reaches than other boats'
#define BOAT_SHIP_WASH_WIDTH 0.85          // how wide their wash is, relative to other boats'
#define BOAT_SHIP_WASH_OFFSET 0.72         // how much further back their wash starts, in tiles on the sloop it's tuned for

#if DAYLIGHT_CYCLE
    #include <utils/sky_sampling.glsl>
#endif

// Distance in steady travel units. Speed falls linearly from SHORE_WAVE_REACH to the shore, so this is the integral of
// its inverse, a logarithm.
float waveTravelDistance(float shoreDist) {
    const float reach = float(SHORE_WAVE_REACH);
    const float endSpeed = clamp(float(SHORE_WAVE_END_SPEED), 0.05, 1.0);
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

// Coverage of waves travelling in to the shore. Each stretch of shore spawns waves at random times, each with its own
// place, length, thickness, speed and strength.
//  shorePoint: nearest point on the shore, in world tiles
//  foamMask:   foam texture, breaking up the fronts
//  room:       room for waves, from 0 to 1
float shoreWaves(float shoreDist, vec2 shorePoint, float foamMask, float room) {
    float time = uboGlobal.elapsedTime;
    float travel = waveTravelDistance(shoreDist) + (foamMask - 0.5) * SHORE_WAVE_FRONT_BREAKUP;
    float startTravel = waveTravelDistance(float(SHORE_WAVE_REACH));
    // Waves thicken as they come in
    float baseThickness = mix(float(SHORE_WAVE_END_THICKNESS), float(SHORE_WAVE_START_THICKNESS), saturate(shoreDist / SHORE_WAVE_REACH));

    // Each stretch may spawn one wave per slot, early enough to stay SHORE_WAVE_MIN_GAP apart
    const float slot = max(SHORE_WAVE_INTERVAL * 0.75, float(SHORE_WAVE_MIN_GAP));
    const float baseChance = min(1.0, slot / SHORE_WAVE_INTERVAL);
    const float spawnWindow = 1.0 - SHORE_WAVE_MIN_GAP / slot;
    const float minSpeed = SHORE_WAVE_SPEED * max(1.0 - SHORE_WAVE_SPEED_RANDOMNESS, 0.01);
    const float maxSpeed = SHORE_WAVE_SPEED * (1.0 + SHORE_WAVE_SPEED_RANDOMNESS);
    const float maxThickness =
        max(float(SHORE_WAVE_START_THICKNESS), float(SHORE_WAVE_END_THICKNESS)) * (1.0 + SHORE_WAVE_THICKNESS_RANDOMNESS);
    const float maxHalfLength = SHORE_WAVE_MAX_LENGTH * 0.5;

    // Only check slots whose waves could be covering this point
    float minAge = max((startTravel - travel) / maxSpeed, 0.0);
    float maxAge = (startTravel - travel + maxThickness) / minSpeed;
    if (maxAge <= 0.0)
        return 0.0;
    float newestSlot = (time - minAge) / slot;
    float oldestSlot = (time - maxAge) / slot - spawnWindow;

    vec2 stretch = floor(shorePoint / SHORE_WAVE_SPACING);
    float wave = 0;
    for (int sy = -1; sy <= 1; sy++) {
        for (int sx = -1; sx <= 1; sx++) {
            vec2 cell = stretch + vec2(sx, sy);
            // Skip stretches too far away to reach
            vec2 toCell = abs(shorePoint - (cell + 0.5) * SHORE_WAVE_SPACING) - 0.5 * SHORE_WAVE_SPACING;
            if (length(max(toCell, 0.0)) >= maxHalfLength)
                continue;

            // Offset each stretch's slots so neighbours don't line up
            float slotOffset = hash12(cell + 0.73);
            float lastN = floor(newestSlot - slotOffset);
            for (float n = floor(oldestSlot - slotOffset); n <= lastN; n++) {
                // Cheapest checks first
                vec2 r1 = hash22(cell + n * vec2(17.31, 5.13));
                vec2 r2 = hash22(cell * 3.71 + n * vec2(2.27, 11.9) + 0.5);
                float age = time - (n + slotOffset + r1.x * spawnWindow) * slot;
                if (age < 0.0)
                    continue;
                float speed = SHORE_WAVE_SPEED * (1.0 + (r2.x - 0.5) * 2.0 * SHORE_WAVE_SPEED_RANDOMNESS);
                float front = startTravel - age * speed;

                // From 0 at the front to 1 at the back
                float thickness = baseThickness *
                    (1.0 + (hash12(cell * 5.17 + n * vec2(3.31, 9.13) + 2.5) - 0.5) * 2.0 * SHORE_WAVE_THICKNESS_RANDOMNESS);
                float across = (travel - front) / thickness;
                if (across <= 0.0 || across >= 1.0)
                    continue;

                // From 0 in the middle to 1 at the ends
                vec2 r3 = hash22(cell * 1.93 - n * vec2(7.13, 3.37) + 1.5);
                vec2 center = (cell + r2) * SHORE_WAVE_SPACING;
                float halfLength = mix(SHORE_WAVE_MIN_LENGTH, SHORE_WAVE_MAX_LENGTH, r3.y) * 0.5;
                float along = length(shorePoint - center) / halfLength;
                if (along >= 1.0)
                    continue;

                // Sets and lulls, rolling along the coast together
                float sets = noise(vec2(n * slot / SHORE_WAVE_SET_PERIOD, dot(cell, vec2(0.37, 0.21))));
                float spawnChance = saturate(baseChance * (1.0 + (sets - 0.5) * 2.0 * SHORE_WAVE_SET_CONTRAST));
                if (r3.x >= spawnChance)
                    continue;

                float strength = mix(SHORE_WAVE_MIN_STRENGTH, 1.0, r1.y) * (1.0 - smoothstep(1.0 - SHORE_WAVE_END_TAPER, 1.0, along));
                float body = smoothstep(0.0, SHORE_WAVE_FRONT, across) * (1.0 - smoothstep(1.0 - SHORE_WAVE_TAIL, 1.0, across));
                // Capped after the boost, so strong waves are solid
                wave = max(wave, body * saturate(strength * room * SHORE_WAVE_COVERAGE));
            }
        }
    }
    return wave;
}

// Squashes an offset along the wind, which stretches shapes measured with it. Only ever applied to small local
// offsets, since rotating a whole coordinate system with the wind would swing distant patterns around wildly.
vec2 stretchAlongWind(vec2 offset, vec2 wind, float stretch) {
    vec2 crossWind = vec2(-wind.y, wind.x);
    return wind * (dot(offset, wind) / stretch) + crossWind * dot(offset, crossWind);
}

// Distance to the nearest edge of a churning Voronoi pattern, as the difference between the two nearest points,
// measured stretched along the wind
float cellEdges(vec2 p, float time, vec2 wind) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    // Squared distances, rooted only for the two nearest
    float nearest = 64.0;
    float second = 64.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(x, y);
            vec2 h = hash22(i + offset);
            vec2 point = offset + 0.5 + 0.4 * sin(time + h * TAU);
            vec2 toPoint = stretchAlongWind(point - f, wind, OCEAN_LACE_STRETCH);
            float d = dot(toPoint, toPoint);
            second = d < nearest ? nearest : min(second, d);
            nearest = min(nearest, d);
        }
    }
    return sqrt(second) - sqrt(nearest);
}

// Wind direction along local X and Z, east if unset
vec2 oceanWind() {
    vec2 wind = uboGlobal.windDirection;
    return dot(wind, wind) > 0.25 ? normalize(wind) : vec2(1.0, 0.0);
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
    return 1.0 - foamLife + (breakup - 0.5) * min(0.55 * OCEAN_WAVE_RAGGEDNESS, 0.8);
}

// How much of a patch of foam is left as it fades out unevenly, from 0 to 1
float foamLasting(float foamLife, float breakup) {
    return smoothstep(0.0, 0.4, foamLifeLeft(foamLife, breakup));
}

// Foam fading out over the end of its life, from 1 to 0, shared by the ocean waves and boat wakes
float foamEndFade(float life) {
    return 1.0 - smoothstep(0.85, 1.0, life);
}

// Ocean wave coverage and foam age, from 0 to 1. Each crest sweeps downwind, leaving a trail that breaks up from the
// back. Each grid cell has a wave some of the time.
//  q:            position in wave cells
//  shoreDist:    distance to the shore, in tiles
//  towardsShore: direction to the shore, or 0 where unknown
vec2 oceanWaveCells(vec2 q, float time, float shoreDist, vec2 towardsShore) {
    // In wave radii
    const float maxSweep = 1.7;
    // In wave cells
    const float maxReach = 1.8;
    // Shared noise, worked out once a wave is nearby
    bool noiseReady = false;
    float ragged = 0.0;
    float breakup = 0.0;
    vec2 cell0 = floor(q);
    float result = 0.0;
    float age = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 cell = cell0 + vec2(x, y);
            // Centers lie in the middle half of each cell
            vec2 toCenters = max(abs(q - (cell + 0.5)) - 0.25, 0.0);
            if (dot(toCenters, toCenters) > maxReach * maxReach)
                continue;
            vec2 h = hash22(cell + 0.31);
            // Each cell may spawn a wave once per lifetime, with a chance set by the roughness when it spawns, so
            // waves already going don't appear or vanish as the roughness changes
            float cycle = time / OCEAN_WAVE_LIFETIME + h.x;
            float n = floor(cycle);
            // Cheapest checks first
            vec2 center = cell + 0.25 + 0.5 * hash22(cell * 1.7 + n * vec2(7.31, 3.17));
            vec2 offset = q - center;
            if (dot(offset, offset) > maxReach * maxReach)
                continue;
            float spawnTime = (n - h.x) * OCEAN_WAVE_LIFETIME;
            int historySecond = int(mod(floor(spawnTime), float(WIND_HISTORY_LENGTH)));
            float spawnRoughness = uboGlobal.roughnessHistory[historySecond >> 2][historySecond & 3];
            spawnRoughness = spawnRoughness > 0.0 ? spawnRoughness : 1.0;
            float chance = saturate(OCEAN_WAVE_AMOUNT * pow(spawnRoughness, OCEAN_WAVE_ROUGHNESS_AMOUNT));
            if (hash12(cell * 5.3 + n * vec2(2.71, 6.13)) >= chance)
                continue;
            vec4 r = hash42(cell * 2.9 + n * vec2(1.73, 9.21));
            float life = fract(cycle) * (1.0 + r.x * OCEAN_WAVE_RATE_RANDOMNESS * 1.5);
            if (life >= 1.0)
                continue;
            // Each wave keeps the wind from when it spawned, so a change in the wind only affects new waves
            float windAngle = uboGlobal.windHistory[historySecond >> 2][historySecond & 3];
            vec2 wind = vec2(cos(windAngle), sin(windAngle));
            // Stretched along that wind around its own centre
            offset = stretchAlongWind(offset, wind, OCEAN_WAVE_STRETCH);
            float radius = mix(0.13, 0.42, h.y);
            float angle = windAngle + (r.y - 0.5) * (PI / 7.0);
            vec2 downwind = vec2(cos(angle), sin(angle));
            vec4 shape = hash42(cell * 4.13 + n * vec2(5.31, 2.87));
            float sweep = maxSweep * mix(0.6, 1.0, shape.x);
            // Long crests are slimmer, to fit the neighbouring cells
            float crestLength = mix(2.4, 4.0, r.z);
            // Bigger in rougher water, from when it spawned, still fitting the neighbouring cells
            radius = min(radius * pow(spawnRoughness, OCEAN_WAVE_ROUGHNESS_SIZE), 0.9 / crestLength);
            // Downwind in radii, and along the crest from -1 to 1, one side longer
            float u = dot(offset, downwind) / radius;
            float v = dot(offset, vec2(-downwind.y, downwind.x)) / (radius * crestLength);
            v /= v > 0.0 ? mix(0.6, 1.0, shape.z) : mix(1.0, 0.6, shape.z);
            if (abs(u) > maxSweep || abs(v) > 1.6)
                continue;

            if (!noiseReady) {
                noiseReady = true;
                // Ragged edges, from broad lumps to fine tears
                ragged = (noise(q * 2.2 + time * vec2(0.05, -0.04)) - 0.5) * 0.3;
                ragged += (noise(q * 5.1 + time * vec2(-0.07, 0.06) + 9.3) - 0.5) * 0.18;
                ragged += (noise(q * 11.3 + time * vec2(0.09, 0.08) + 4.1) - 0.5) * 0.14;
                ragged += (noise(q * 23.7 + time * vec2(-0.12, 0.1) + 17.9) - 0.5) * 0.1;
                ragged *= OCEAN_WAVE_RAGGEDNESS;
                breakup = foamBreakupNoise(q, time);
            }

            // The crest eases up to speed and back to a stop, its ends lagging so it curves
            const float breakEnd = 0.65;
            float travelled = smoothstep(0.0, 1.0, saturate(life / breakEnd)) * sweep;
            float crest = travelled - 0.5 * sweep;
            float curve = mix(0.05, 0.7, shape.y) * v * v;
            // Wiggles along the front at three scales
            vec3 wiggleRates = mix(vec3(2.0, 5.0, 11.0), vec3(4.0, 9.0, 17.0), vec3(shape.w, r.y, shape.x));
            vec3 wiggles = sin(v * wiggleRates + vec3(r.y, shape.w, r.x) * TAU);
            float waver = dot(wiggles, vec3(0.16, 0.09, 0.05)) * OCEAN_WAVE_WIGGLE;
            // A sharp front, and a trail as long as the crest has travelled, fanning out as it spreads
            float trail = travelled + 0.05;
            float behind = crest - (u + curve + waver);
            // Less ragged at the front, and while the trail is short
            behind += ragged * mix(0.3, 1.0, saturate(behind / trail)) * saturate(trail * 1.5);
            float foamAge = saturate(behind / trail);
            float ends = 1.0 - smoothstep(0.5, 1.0, abs(v) + ragged * 0.8 - foamAge * 0.3);
            // Thinner towards the ends
            float thickness = trail * (1.0 - 0.75 * saturate(v * v));
            float cover = ends * (1.0 - smoothstep(0.0, 0.06, -behind)) * (1.0 - smoothstep(0.7, 1.0, behind / thickness));

            // Foam fades from when the crest passed it, found by inverting the crest's smoothstep
            float passedTravel = saturate((travelled - max(behind, 0.0)) / sweep);
            float passed = (0.5 - sin(asin(1.0 - 2.0 * passedTravel) / 3.0)) * breakEnd;
            float foamLife = max(life - passed, 0.0);
            // Faster waves fade sooner, so their trails don't stretch too far
            float fadeTime = mix(0.3, 0.55, r.w) * (maxSweep * 0.6 / sweep);
            float lasting = foamLifeLeft(foamLife / fadeTime, breakup);
            cover *= smoothstep(0.0, 0.4, lasting);
            // The trail breaks into streaks, more so further behind the front
            if (cover > 0.0) {
                float streaks = noise(vec2(u * 1.6, v * 7.0) + r.zw * 50.0);
                streaks = smoothstep(0.25, 0.7, streaks);
                cover *= mix(1.0, streaks, saturate(behind * 1.5) * OCEAN_WAVE_TAIL_NOISE);
            }
            // Fade in and out over its life
            float fade = smoothstep(0.0, 0.2, life) * foamEndFade(life);

            // Skip waves that would start or end too near the shore, estimating its distance at each end
            if (shoreDist < SHORE_DISTANCE_MAX - 0.3) {
                // The crest's path, unstretched back to tiles
                vec2 path = stretchAlongWind(downwind * (sweep * radius), wind, 1.0 / OCEAN_WAVE_STRETCH);
                vec2 startOffset = (center - q - 0.5 * path) * OCEAN_WAVE_SIZE;
                vec2 endOffset = startOffset + path * OCEAN_WAVE_SIZE;
                float pathShoreDist = shoreDist - max(dot(startOffset, towardsShore), dot(endOffset, towardsShore));
                fade *= smoothstep(OCEAN_FOAM_SHORE_GAP, OCEAN_FOAM_SHORE_FADE, pathShoreDist);
            }
            cover *= fade;
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

    // Fade out with distance from the camera, like shadows, no further than the detail distance setting, so lowering it
    // for performance shortens the lace too. Measured across the water, ignoring the camera's height, which grows as
    // it pitches down, so looking straight down doesn't push the lace out of range.
    float laceDistance = length(IN.position.xz - uboGlobal.cameraPos.xz) / 128.0;
    float maxDistance = uboGlobal.detailDrawDistance > 0.0 ?
        min(float(OCEAN_LACE_DISTANCE), uboGlobal.detailDrawDistance) : float(OCEAN_LACE_DISTANCE);
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
    float lace = 0.0;
    if (gaps > 0.0)
        lace = (1.0 - smoothstep(0.0, width, cellEdges(p, churnTime * OCEAN_LACE_CHURN_SPEED, wind))) * gaps;

    // Denser areas come and go
    float wt = time * OCEAN_LACE_DENSE_SPEED;
    float dense = noise(drifted / OCEAN_LACE_DENSE_SIZE + vec2(wt, -0.7 * wt));
    dense = smoothstep(1.0 - OCEAN_LACE_DENSE_AMOUNT - 0.15, 1.0 - OCEAN_LACE_DENSE_AMOUNT + 0.15, dense);

    lace *= mix(OCEAN_LACE_COVERAGE, OCEAN_LACE_DENSE_COVERAGE, dense) * laceFade;
    return lace;
}

// Ocean wave coverage, from 0 to 1, and brightness relative to OCEAN_WAVE_STRENGTH
//  drifted: position in world tiles, drifted with the wind
vec2 oceanWaves(vec2 drifted, float shoreDist, vec2 towardsShore) {
    vec2 q = drifted / OCEAN_WAVE_SIZE;
    vec2 cells = oceanWaveCells(q, uboGlobal.elapsedTime, shoreDist, towardsShore);
    // Brightest and densest at the front
    float front = 1.0 - smoothstep(0.0, 0.6, cells.y);
    float coverage = cells.x * mix(OCEAN_WAVE_COVERAGE, 1.0, front);
    // With a little extra brightness right at the crest's edge, tailing off softly
    float behind = cells.y / OCEAN_WAVE_EDGE_WIDTH;
    float edge = 1.0 / (1.0 + behind * behind);
    return vec2(coverage, mix(1.0, OCEAN_WAVE_FRONT_BRIGHTNESS, front) + OCEAN_WAVE_EDGE_BRIGHTNESS * edge);
}

// Foam a hull pushed out, from 0 to 1, how far through its life it is, by whichever of the wake or wash has more here,
// and how much of the denser rim along the wake's outer edge it is, from 0 to 1. It peels off the bow and pulls away
// from the hull at an angle, widening as it goes, then behind the stern drifts outwards, spreads and breaks up unevenly
// where it was laid, like the ocean waves. Behind the stern, the boat wash churns down the middle.
//  world: position in world tiles
//  side: distance from the hull's centre line, or the path behind the stern
//  fromBow: distance back from the bow, in tiles
//  age: seconds since the stern passed, 0 alongside the hull
//  arms: false for just the wash, for a boat going backwards
//  squareBow: true for a boat with a square front, like a raft, whose foam starts at full width
//  shape: for the big ships, their arms' angle, how long the arms last and how far the wash reaches, relative to other
//  boats', then how much further back the wash starts, in tiles, or 1, 1, 1, 0
//  washScale: how wide the wash is, relative to other boats'
//  noises: the noise at this spot, shared by every wake here, each worked out the first time it's needed, or -1 until
//  then: the breakup, the raggedness, then two used after this
vec3 boatWakeFoam(vec2 world, float side, float fromBow, float age, bool arms, bool squareBow, vec4 shape,
    float washScale, inout vec4 noises) {
    float washStart = BOAT_WAKE_BOW_LENGTH + BOAT_WASH_OFFSET + shape.w;
    float angleSlope = tan(radians(BOAT_WAKE_ANGLE)) * shape.x;
    float life = arms ? age / (BOAT_WAKE_LIFETIME * shape.y) : 1.0;
    // The wash lasts its lifetime, or until it's its length behind the stern, whichever is later, so it still shows
    // behind a slow boat rather than all under its stern
    float washLife = min(age / BOAT_WASH_LIFETIME, (fromBow - washStart) / BOAT_WASH_LENGTH) / shape.z;
    if (life >= 1.0 && washLife >= 1.0)
        return vec3(0.0);

    float alongHull = min(fromBow, BOAT_HULL_LENGTH);
    float hullEdge = BOAT_HULL_WIDTH * 0.5 * (squareBow ? 1.0 : sqrt(saturate(fromBow / BOAT_HULL_BOW_LENGTH)));
    float foamLine = hullEdge + alongHull * angleSlope + age * BOAT_WAKE_DRIFT * shape.x;
    float spread = BOAT_WAKE_WIDTH + alongHull * BOAT_WAKE_HULL_SPREAD + age * BOAT_WAKE_SPREAD;
    float raggedness = BOAT_WAKE_RAGGEDNESS * (0.3 + alongHull * 0.15 + age);
    // Narrowing to a point towards its end
    float washWidth = (BOAT_WASH_WIDTH * 0.5 + age * BOAT_WASH_SPREAD) * washScale *
        (1.0 - smoothstep(min(BOAT_WASH_TAPER, 0.999), 1.0, washLife)) + 1e-4;
    // Nothing out beyond the furthest the wake's outer edge, while it lasts, or the wash can reach, so skip the noise
    if (side >= max(life < 1.0 ? foamLine + spread * 0.5 : 0.0, washWidth) + raggedness * 0.5)
        return vec3(0.0);
    // Fading out the same way as the ocean waves' foam
    if (noises.x < 0.0)
        noises.xy = vec2(foamBreakupNoise(world * BOAT_WAKE_BREAKUP_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime),
            noise(world * 1.3));
    float breakup = noises.x;
    float ragged = (noises.y - 0.5) * raggedness;
    // Across each band, from 0 in its middle to 1 at its edge
    float offset = side - foamLine + ragged;
    float across = abs(offset) / (spread * 0.5 * (offset < 0.0 ? BOAT_WAKE_INNER_REACH : 1.0));
    // A sharp edge on the outside, where the wave the hull pushes out breaks, with denser foam along it like the ocean
    // waves' fronts
    float foam = 1.0 - smoothstep(offset > 0.0 ? 1.0 - BOAT_WAKE_RIM_WIDTH * 0.5 : 0.6, 1.0, across);
    const float rimStart = 1.0 - BOAT_WAKE_RIM_WIDTH;
    float rim = offset > 0.0 ? smoothstep(rimStart - max(BOAT_WAKE_RIM_BLEND, 1e-3), rimStart, across) : 0.0;
    foam *= foamLasting(life, breakup) * foamEndFade(life);

    float washAcross = (side + ragged) / washWidth;
    float wash = (1.0 - smoothstep(0.5, 1.0, washAcross)) *
        smoothstep(washStart - 0.5, washStart + 0.5, fromBow);
    wash *= foamLasting(washLife, breakup) * foamEndFade(washLife);

    return foam >= wash ? vec3(foam, life, rim) : vec3(wash, washLife, 0.0);
}

// Boat wake coverage, opacity and rim, each from 0 to 1, along one path per boat: from its bow to its stern as it is
// now, so the foam hugs the hull through turns, then back along the path the stern took, so the foam stays where it was
// laid.
//  p: position in tiles relative to the scene
vec3 boatWakes(vec2 p) {
    // In world tiles, so the noise doesn't jump when the scene's base moves at loading lines
    vec2 world = p + vec2(uboGlobal.sceneBase) / 128.0;
    vec3 wake = vec3(0.0);
    // The noise here, worked out once for all the wakes, when first needed
    vec4 noises = vec4(-1.0);
    for (int boat = 0; boat < BOAT_WAKE_MAX_BOATS; boat++) {
        vec4 hull = uboGlobal.boatWakeHulls[boat];
        int count = int(hull.x);
        // Wakes fill the slots in order, so the first empty one means the rest are too
        if (count < 1)
            break;
        // Nothing left once even the newest foam has broken up
        if (hull.y >= BOAT_WAKE_STOP_TIME)
            continue;

        // How much bigger or smaller this boat is than the one the wake is tuned for, along and across
        vec4 size = uboGlobal.boatWakeSizes[boat];
        bool bigShip = size.w > 0.5;
        vec4 shape = bigShip ? vec4(BOAT_SHIP_ANGLE, BOAT_SHIP_LIFETIME, BOAT_SHIP_WASH_LENGTH, BOAT_SHIP_WASH_OFFSET) :
            vec4(1.0, 1.0, 1.0, 0.0);
        float washScale = bigShip ? BOAT_SHIP_WASH_WIDTH : 1.0;
        float lengthScale = size.x > 0.0 ? size.x / BOAT_REFERENCE_LENGTH : 1.0;
        float sideScale = size.y > 0.0 ? size.y / BOAT_REFERENCE_WIDTH : 1.0;
        float bowLength = BOAT_WAKE_BOW_LENGTH * lengthScale;

        // The hull's points, then the path's bounds with the bow added, and room for the foam
        vec4 headingStern = uboGlobal.boatWakeHeadings[boat];
        // Shorter boats' wakes sit a little further forward, longer ones' further back, except rafts', which are centred
        // on their positions
        float forward = size.z > 0.5 ? 0.0 : (BOAT_REFERENCE_LENGTH - size.x) * BOAT_WAKE_SIZE_FORWARD;
        if (bigShip)
            forward += BOAT_SHIP_FORWARD;
        vec2 sternPos = headingStern.zw + headingStern.xy * forward;
        vec2 bowPos = sternPos + headingStern.xy * bowLength;
        vec4 bounds = uboGlobal.boatWakeBounds[boat];
        int first = boat * BOAT_WAKE_MAX_POINTS;
        // The furthest out from its path this wake's foam can reach, in tiles, from the age of its oldest point, the end
        // of its path, plus the ageing after the boat stops. The arms are gone sooner.
        float oldest = uboGlobal.boatWakePoints[first + count - 1].z + hull.y * BOAT_WAKE_STOP_AGEING;
        float armAge = min(BOAT_WAKE_LIFETIME * shape.y, oldest);
        float band = BOAT_HULL_WIDTH * 0.5 +
            (BOAT_WAKE_BOW_LENGTH * tan(radians(BOAT_WAKE_ANGLE)) + armAge * BOAT_WAKE_DRIFT) * shape.x +
            0.5 * (BOAT_WAKE_WIDTH + BOAT_HULL_LENGTH * BOAT_WAKE_HULL_SPREAD + armAge * BOAT_WAKE_SPREAD);
        float washReach = (BOAT_WASH_WIDTH * 0.5 + oldest * BOAT_WASH_SPREAD) * washScale;
        float ragged = 0.5 * BOAT_WAKE_RAGGEDNESS * (0.3 + BOAT_HULL_LENGTH * 0.15 + oldest);
        float boatReach = (max(band, washReach) + ragged) * max(lengthScale, sideScale);
        vec2 boundsMin = min(bounds.xy, bowPos) - boatReach;
        vec2 boundsMax = max(bounds.zw, bowPos) + boatReach;
        if (any(lessThan(p, boundsMin)) || any(greaterThan(p, boundsMax)))
            continue;

        // The nearest point on the path, how far back from the bow it is, and how long ago the stern passed it. Points
        // hold their position, age, then distance back from the bow.
        float nearest = 1e9;
        float fromBow = 0.0;
        float age = 0.0;
        bool outside = false;
        bool firstSegment = true;
        int total = count + 2;
        vec4 stern = vec4(sternPos, 0.0, bowLength);
        vec4 a = vec4(bowPos, 0.0, 0.0);
        for (int i = 0; i < BOAT_WAKE_MAX_POINTS + 1; i++) {
            if (i + 1 >= total)
                break;
            vec4 b = stern;
            if (i > 0) {
                b = uboGlobal.boatWakePoints[first + i - 1];
                b.w += bowLength;
            }
            vec2 segment = b.xy - a.xy;
            float length2 = dot(segment, segment);
            // Skip segments with no length, like the one just after a point is added where the path starts
            if (length2 >= 1e-6) {
                float t = saturate(dot(p - a.xy, segment) / length2);
                vec2 offset = p - (a.xy + segment * t);
                float dist2 = dot(offset, offset);
                if (dist2 < nearest) {
                    nearest = dist2;
                    fromBow = mix(a.w, b.w, t);
                    age = mix(a.z, b.z, t);
                    // In front of the bow, or past the end of the path
                    outside = (firstSegment && t <= 0.0) || (i + 2 == total && t >= 1.0);
                }
                firstSegment = false;
            }
            a = b;
        }
        if (outside)
            continue;

        // Shaped as for the boat it's tuned for, scaled to this one. Ages are sent as of when the boat slowed, so the
        // time since is added, more slowly, to keep the foam ageing after it stops without the tail wiping away
        // towards the boat too fast.
        vec3 foamLife = boatWakeFoam(world, sqrt(nearest) / sideScale, fromBow / lengthScale,
            age + hull.y * BOAT_WAKE_STOP_AGEING, hull.w < 0.5, size.z > 0.5, shape, washScale, noises);
        float foam = foamLife.x;
        // The fades only take foam away, so skip them where another wake already has more
        if (foam <= wake.x)
            continue;
        // Any foam here means the breakup and raggedness are already worked out
        if (noises.z < 0.0)
            noises.zw = vec2(noise(world * 1.3 + 7.1),
                foamBreakupNoise(world * BOAT_WAKE_STOP_ALPHA_SCALE / OCEAN_WAVE_SIZE, uboGlobal.elapsedTime));
        float breakup = noises.x;
        // Fading out raggedly towards the end of the tail, rather than cut off straight across where the path starts,
        // which shows while a new tail is growing in. Broken up into patches along the fade, like the rest of the
        // wake, so it isn't an even ramp. The last point is the whole path's length back from the bow.
        float endEdge = a.w - fromBow - noises.z * BOAT_WAKE_END_RAGGEDNESS;
        foam *= smoothstep(0.0, 1.0, endEdge / BOAT_WAKE_END_SOFTNESS - breakup * BOAT_WAKE_END_BREAKUP);
        // How far the wake has broken up since the boat slowed
        float fadeOut = saturate(hull.y / BOAT_WAKE_STOP_TIME);
        if (fadeOut > 0.0) {
            // Breaking up evenly across the whole wake, the same way as the waves' foam, but with each patch fading
            // more softly. Starting from the point where its most ragged parts begin to go, so it starts breaking up
            // straight away.
            float start = 1.0 - BOAT_WAKE_BREAKUP_SOFTNESS - 0.5 * min(0.55 * OCEAN_WAVE_RAGGEDNESS, 0.8);
            float life = mix(start, 1.0, fadeOut);
            foam *= smoothstep(0.0, BOAT_WAKE_BREAKUP_SOFTNESS, foamLifeLeft(life, breakup)) * foamEndFade(life);
        }
        // Fading in over time since it moved off, and out as it breaks up, some parts turning see-through sooner than
        // others, still all gone by the end
        float fadeIn = saturate(hull.z / BOAT_WAKE_START_TIME);
        const float unevenness = BOAT_WAKE_STOP_ALPHA_UNEVENNESS;
        float alpha = fadeIn * (1.0 - saturate(fadeOut * (1.0 + unevenness) - noises.w * unevenness));
        // Turning see-through as the oldest foam at the end of the tail breaks up
        alpha *= 1.0 - smoothstep(BOAT_WAKE_END_ALPHA_START, 1.0, foamLife.y);
        if (foam > wake.x)
            wake = vec3(foam, alpha, foamLife.z);
    }
    return wake;
}

// Foam built from texture specks, thinning with the coverage and fading at its edges
float oceanSpecks(float coverage, float softness, float specks) {
    return smoothstep(1.0 - coverage, 1.0 - coverage + softness, specks) * smoothstep(0.0, 0.7, coverage);
}

vec4 sampleWater(int waterTypeIndex, vec3 viewDir) {
    WaterType waterType = getWaterType(waterTypeIndex);

    vec2 anchorUv1 = worldUvs(3).yx;
    vec2 anchorUv2 = worldUvs(5);
    vec2 anchorUv3 = worldUvs(7);
    vec2 uv1 = anchorUv1 - animationFrame(28 * waterType.duration);
    vec2 uv2 = anchorUv2 + animationFrame(24 * waterType.duration);
    vec2 uv3 = anchorUv3 + animationFrame(21 * waterType.duration);

    vec2 flowMapUv = worldUvs(15) + animationFrame(50 * waterType.duration);
    float flowMapStrength = 0.025;

    vec2 uvFlow = texture(textureArray, vec3(flowMapUv, MAT_WATER_FLOW_MAP.colorMap)).xy;
    uv1 += uvFlow * flowMapStrength;
    uv2 += uvFlow * flowMapStrength;
    uv3 += uvFlow * flowMapStrength;

    // get diffuse textures
    vec3 n1 = linearToSrgb(texture(textureArray, vec3(uv1, waterType.normalMap)).xyz);
    vec3 n2 = linearToSrgb(texture(textureArray, vec3(uv2, waterType.normalMap)).xyz);
    vec3 n3 = linearToSrgb(texture(textureArray, vec3(uv3, waterType.normalMap)).xyz);
    #if IMPROVED_SHORE_FOAM
        float foamMask = 0;
    #else
        vec2 uvFoam = IN.uv + uvFlow * flowMapStrength;
        float foamMask = texture(textureArray, vec3(uvFoam, MAT_WATER_FOAM.colorMap)).r;
    #endif

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
    // Distance to land per vertex. See SceneContext for the shore data layout.
    ivec3 shoreDistances = fAlphaBiasHsl & SHORE_DISTANCE_STEPS;
    #if IMPROVED_SHORE_FOAM
    float foamAmount = 0;
    // Skip faces neither near the shore nor on open water. Uniform per face, so texture derivatives stay valid. Cast,
    // since max is ambiguous with whole number settings.
    float foamReach = max(float(SHORE_WAVE_REACH), float(SHORE_FOAM_REACH));
    int foamReachSteps = int(ceil((foamReach + SHORE_FOAM_EDGE_NOISE) / SHORE_DISTANCE_MAX * SHORE_DISTANCE_STEPS));
    ivec3 enclosureSteps = fAlphaBiasHsl >> WATER_ENCLOSURE_SHIFT & WATER_ENCLOSURE_STEPS;
    const int openWaterSteps = int(ceil((1.0 - float(SHORE_WAVE_MIN_ROOM) / WATER_ROOM_MAX) * WATER_ENCLOSURE_STEPS));
    bool nearShore = bool(SHORE_FOAM) && any(lessThan(shoreDistances, ivec3(foamReachSteps)));
    bool openWater = (bool(OCEAN_LACE) || bool(OCEAN_WAVES)) && any(lessThan(enclosureSteps, ivec3(openWaterSteps)));
    // Rougher water, from the wind strength, has more and stronger waves, more visible lace and faster churn. Normal
    // until the roughness is first set.
    float roughness = uboGlobal.waterRoughness > 0.0 ? uboGlobal.waterRoughness : 1.0;
    float waveStrength = OCEAN_WAVE_STRENGTH * pow(roughness, OCEAN_WAVE_ROUGHNESS_STRENGTH);
    // Foam specks for the ocean waves, lace and boat wakes, drifting with the wind and warped by the flow map.
    // Derivatives taken here, where they're valid.
    vec2 speckUv = worldUvs(OCEAN_FOAM_TEXTURE_SCALE) +
        uboGlobal.windDrift * (OCEAN_FOAM_DRIFT_SPEED / OCEAN_FOAM_TEXTURE_SCALE) + uvFlow * flowMapStrength;
    vec2 speckUvDx = dFdx(speckUv);
    vec2 speckUvDy = dFdy(speckUv);
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

        // Direction to land, where known. Blending vectors avoids wrapping, and weakens where vertices disagree.
        vec2 shoreDir = vec2(0);
        vec2 towardsShore = vec2(0);
        if (any(lessThan(shoreDistances, ivec3(SHORE_DISTANCE_STEPS)))) {
            vec3 shoreAngles = vec3(fAlphaBiasHsl >> SHORE_DIRECTION_SHIFT & SHORE_DIRECTION_STEPS - 1) *
                (TAU / SHORE_DIRECTION_STEPS);
            shoreDir = vec2(dot(fTexBlend, cos(shoreAngles)), dot(fTexBlend, sin(shoreAngles)));
            towardsShore = length(shoreDir) > 0.1 ? normalize(shoreDir) : vec2(0);
        }
        // Nearest point on the shore, in world tiles, which stays put as waves come in
        vec2 shorePoint = -worldUvs(1.0) + towardsShore * shoreDist;

        // No waves in small ponds or narrow channels
        float waterRoom = (1.0 - dot(fTexBlend, vec3(enclosureSteps)) / WATER_ENCLOSURE_STEPS) * WATER_ROOM_MAX;
        float roomForWaves = smoothstep(SHORE_WAVE_MIN_ROOM, SHORE_WAVE_FULL_ROOM, waterRoom);

        // Open water foam specks, drifting with the wind. Sampled before per-pixel branches, for valid derivatives.
        float oceanMask = 0.0;
        if (openWater) {
            oceanMask = smoothstep(0.1, 0.65, texture(textureArray, vec3(speckUv, MAT_WATER_FOAM.colorMap)).r);
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
            sway *= 1.0 - smoothstep(foamReach, 4.0, shoreDist);
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
            if (roomForWaves > 0.0 && waveOpacity > 0.0)
                wave = shoreWaves(shoreDist, shorePoint, foamMask, roomForWaves);
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
        if (oceanRoom > 0.0) {
            vec2 drifted = -worldUvs(1.0) - uboGlobal.windDrift * OCEAN_FOAM_DRIFT_SPEED;
            vec2 wind = oceanWind();
            float churnTime = uboGlobal.waterRoughness > 0.0 ? uboGlobal.waterChurnTime : uboGlobal.elapsedTime;
            if (bool(OCEAN_LACE)) {
                float laceRoughness = pow(roughness, OCEAN_LACE_ROUGHNESS);
                // Denser as well as stronger, up to solid line cores
                float lace = saturate(oceanLace(drifted, wind, churnTime) * sqrt(laceRoughness)) * oceanRoom;
                float laceStrength = OCEAN_LACE_STRENGTH * laceRoughness;
                foamAmount = max(foamAmount, oceanSpecks(lace, OCEAN_LACE_SOFTNESS, oceanMask) * laceStrength);
            }
            if (bool(OCEAN_WAVES)) {
                vec2 waves = oceanWaves(drifted, shoreDist, towardsShore);
                float amount = oceanSpecks(waves.x * oceanRoom, OCEAN_WAVE_SOFTNESS, oceanMask);
                foamAmount = max(foamAmount, amount * waveStrength * waves.y);
            }
        }
    }
    // Boat wakes, separate from the rest of the foam
    if (bool(BOAT_WAKES) && waterType.hasFoam != 0) {
        vec3 wake = boatWakes(IN.position.xz / 128.0);
        if (wake.x > 0.0) {
            float specks = smoothstep(0.1, 0.65,
                textureGrad(textureArray, vec3(speckUv, MAT_WATER_FOAM.colorMap), speckUvDx, speckUvDy).r);
            // Following the ocean waves' strength here, which rougher water raises
            // Specks as soft as the ocean waves', denser along the rim like their fronts
            float amount = oceanSpecks(wake.x * mix(BOAT_WAKE_COVERAGE, 1.0, wake.z), OCEAN_WAVE_SOFTNESS, specks);
            float rimBrightness = mix(1.0, BOAT_WAKE_RIM_BRIGHTNESS, wake.z);
            foamAmount = max(foamAmount, amount * wake.y * waveStrength * BOAT_WAKE_STRENGTH * rimBrightness);
            foamMask = max(foamMask, specks);
        }
    }
    // With the new shore foam off, shores keep the original fade along tile edges, lit the original way below
    float originalFoam = 0.0;
    float originalMask = 0.0;
    if (!bool(SHORE_FOAM)) {
        float shoreLineMask = 1 - dot(fTexBlend, vec3(notEqual(shoreDistances, ivec3(0))));
        originalFoam = clamp(pow(1.0 - ((1.0 - min(shoreLineMask, 0.8)) / 0.7), 3), 0.0, 1.0) * waterType.hasFoam;
        vec2 uvFoam = IN.uv + uvFlow * flowMapStrength;
        originalMask = texture(textureArray, vec3(uvFoam, MAT_WATER_FOAM.colorMap)).r;
    }
    #else
        // The original foam only checks whether each vertex is on the shore
        float shoreLineMask = 1 - dot(fTexBlend, vec3(notEqual(shoreDistances, ivec3(0))));
        float foamAmount = min(shoreLineMask, 0.8);
        foamAmount = clamp(pow(1.0 - ((1.0 - foamAmount) / 0.7), 3), 0.0, 1.0) * waterType.hasFoam;
    #endif
    float opacityFresnel = max(finalFresnel, 1.0 - dot(viewDir, vec3(0, -1, 0)));

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

        #if IMPROVED_SHORE_FOAM
            vec3 foamColor = vec3(0);
            float litFoamAmount = 0;
            if (foamAmount > 0) {
                vec3 foamAlbedo = waterType.foamColor * mix(0.5, 0.7, foamMask);
                foamColor = foamAlbedo * compositeLight;
                // At least FOAM_WATER_BRIGHTNESS times the water's brightness, since in dim light like sunrise and
                // sunset the water is lit mostly by the sky's reflection. Shaded by the texture, capped at about white,
                // and tinted by the color of the light, so it isn't grey at sunrise and sunset.
                float waterBrightness = linearSrgbLuminance(max(baseColor, vec3(0)));
                vec3 lightTint = max(compositeLight, vec3(0));
                lightTint /= max(linearSrgbLuminance(lightTint), 1e-4);
                foamColor = max(foamColor, foamAlbedo / 0.6 * lightTint * min(waterBrightness * FOAM_WATER_BRIGHTNESS, 1.3));
                // The color carries the lighting, so only fade the opacity in real darkness
                float brightness = linearToSrgb(linearSrgbLuminance(srgbToLinear(saturate(foamColor))));
                litFoamAmount = foamAmount * saturate(brightness * 4.0);
                // More opaque under a low sun, where the sky's bright reflection would otherwise wash it out
                float sunHeight = -uboGlobal.lightDir.y;
                #if DAYLIGHT_CYCLE
                // The sun itself, since the light switches over to the moon at night
                if (uboSky.enabled)
                    sunHeight = uboSky.sunDir.y;
                #endif
                // easing back off as it sets, when the sky dims too
                float lowSun = (1.0 - smoothstep(0.0, 0.5, sunHeight)) * smoothstep(0.0, 0.2, sunHeight);
                litFoamAmount = saturate(litFoamAmount * (1.0 + FOAM_LOW_SUN_OPACITY * lowSun));
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
        #else
            vec3 foamColor = waterType.foamColor * foamMask * compositeLight;
            float litFoamAmount = foamAmount * foamColor.r;
        #endif
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
