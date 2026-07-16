#pragma once

#include <utils/misc.glsl>

#include MAX_CHARACTER_POSITION_COUNT
#include MAX_BOAT_COUNT

#define BOAT_CONTOUR 8
#define BOAT_DIST_OFFSET 42.0

struct Boat {
    // packed XY half16
    ivec4 boatContour[BOAT_CONTOUR / 4];
};

layout(std140) uniform UBODisplacement {
    float windDirectionX;
    float windDirectionZ;
    float windStrength;
    float windCeiling;
    float windOffset;

    int characterPositionCount;
    int boatCount;

    vec3 characterPositions[MAX_CHARACTER_POSITION_COUNT];
    Boat boats[MAX_BOAT_COUNT];
};

vec2 unpackBoatPoint(Boat boat, int vertex) {
    return unpackFloat2x16(boat.boatContour[vertex / 4][vertex % 4]);
}

float boatDistance(Boat boat, vec2 p) {
    vec2 v0 = unpackBoatPoint(boat, 0);
    float d = dot(p - v0, p - v0);
    float s = 1.0;

    for (int i = 0; i < BOAT_CONTOUR; i++) {
        int j = (i + BOAT_CONTOUR - 1) % BOAT_CONTOUR;
        vec2 vi = unpackBoatPoint(boat, i);
        vec2 vj = unpackBoatPoint(boat, j);

        vec2 e = vj - vi;
        vec2 w = p - vi;
        vec2 b = w - e * clamp(dot(w, e) / dot(e, e), 0.0, 1.0);
        d = min(d, dot(b, b));

        // winding-number style inside/outside test
        bvec3 c = bvec3(
            p.y >= vi.y,
            p.y <  vj.y,
            e.x * w.y > e.y * w.x
        );
        if (all(c) || all(not(c))) s = -s;
    }

    return (s * sqrt(d)) + BOAT_DIST_OFFSET;
}