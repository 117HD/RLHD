/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

#include utils/constants.glsl

layout(binding = 3) uniform isampler3D tileHeightMap;

// Calculate adjusted priority for a face with a given priority, distance, and
// model global min10 and face distance averages. This allows positioning faces
// with priorities 10/11 into the correct 'slots' resulting in 18 possible
// adjusted priorities
int priority_map(int p, int distance, int _min10, int avg1, int avg2, int avg3) {
    // (10, 11)  0  1  2  (10, 11)  3  4  (10, 11)  5  6  7  8  9  (10, 11)
    //   0   1   2  3  4    5   6   7  8    9  10  11 12 13 14 15   16  17
    switch (p) {
        case 0: return 2;
        case 1: return 3;
        case 2: return 4;
        case 3: return 7;
        case 4: return 8;
        case 5: return 11;
        case 6: return 12;
        case 7: return 13;
        case 8: return 14;
        case 9: return 15;
        case 10:
        if (distance > avg1) {
            return 0;
        } else if (distance > avg2) {
            return 5;
        } else if (distance > avg3) {
            return 9;
        } else {
            return 16;
        }
        case 11:
        if (distance > avg1 && _min10 > avg1) {
            return 1;
        } else if (distance > avg2 && (_min10 > avg1 || _min10 > avg2)) {
            return 6;
        } else if (distance > avg3 && (_min10 > avg1 || _min10 > avg2 || _min10 > avg3)) {
            return 10;
        } else {
            return 17;
        }
        default:
        // this can't happen unless an invalid priority is sent. just assume 0.
        return 0;
    }
}

// calculate the number of faces with a lower adjusted priority than
// the given adjusted priority
int count_prio_offset(int priority) {
    // this shouldn't ever be outside of (0, 17) because it is the return value from priority_map
    priority = clamp(priority, 0, 17);
    int total = 0;
    for (int i = 0; i < priority; i++) {
        total += totalMappedNum[i];
    }
    return total;
}

void get_face(
    uint localId, ModelInfo minfo,
    out int prio, out int dis, out vert o1, out vert o2, out vert o3
) {
    int size = minfo.size;
    int offset = minfo.offset;
    int flags = minfo.flags;
    uint ssboOffset;

    if (localId < size) {
        ssboOffset = localId;
    } else {
        ssboOffset = 0;
    }

    // Grab triangle vertices from the correct buffer
    vert thisA = vb[offset + ssboOffset * 3];
    vert thisB = vb[offset + ssboOffset * 3 + 1];
    vert thisC = vb[offset + ssboOffset * 3 + 2];

    if (localId < size) {
        int orientation = flags & 0x7ff;

        // rotate for model orientation
        thisA.pos = rotate(thisA.pos, orientation);
        thisB.pos = rotate(thisB.pos, orientation);
        thisC.pos = rotate(thisC.pos, orientation);

        // calculate distance to face
        int thisPriority = (thisA.ahsl >> 16) & 0xF;// all vertices on the face have the same priority
        int thisDistance = face_distance(thisA.pos, thisB.pos, thisC.pos);

        o1 = thisA;
        o2 = thisB;
        o3 = thisC;
        prio = thisPriority;
        dis = thisDistance;
    } else {
        o1 = vert(vec3(0), 0);
        o2 = vert(vec3(0), 0);
        o3 = vert(vec3(0), 0);
        prio = 0;
        dis = 0;
    }
}

void add_face_prio_distance(uint localId, ModelInfo minfo, vert thisrvA, vert thisrvB, vert thisrvC, int thisPriority, int thisDistance, vec3 pos) {
    if (localId < minfo.size) {
        // if the face is not culled, it is calculated into priority distance averages
        if (face_visible(thisrvA.pos, thisrvB.pos, thisrvC.pos, pos)) {
            atomicAdd(totalNum[thisPriority], 1);
            atomicAdd(totalDistance[thisPriority], thisDistance);

            // calculate minimum distance to any face of priority 10 for positioning the 11 faces later
            if (thisPriority == 10) {
                atomicMin(min10, thisDistance);
            }
        }
    }
}

int map_face_priority(uint localId, ModelInfo minfo, int thisPriority, int thisDistance, out int prio) {
    int size = minfo.size;

    // Compute average distances for 0/2, 3/4, and 6/8

    if (localId < size) {
        int avg1 = 0;
        int avg2 = 0;
        int avg3 = 0;

        if (totalNum[1] > 0 || totalNum[2] > 0) {
            avg1 = (totalDistance[1] + totalDistance[2]) / (totalNum[1] + totalNum[2]);
        }

        if (totalNum[3] > 0 || totalNum[4] > 0) {
            avg2 = (totalDistance[3] + totalDistance[4]) / (totalNum[3] + totalNum[4]);
        }

        if (totalNum[6] > 0 || totalNum[8] > 0) {
            avg3 = (totalDistance[6] + totalDistance[8]) / (totalNum[6] + totalNum[8]);
        }

        int adjPrio = priority_map(thisPriority, thisDistance, min10, avg1, avg2, avg3);
        int prioIdx = atomicAdd(totalMappedNum[adjPrio], 1);

        prio = adjPrio;
        return prioIdx;
    }

    prio = 0;
    return 0;
}

void insert_face(uint localId, ModelInfo minfo, int adjPrio, int distance, int prioIdx) {
    int size = minfo.size;

    if (localId < size) {
        // calculate base offset into renderPris based on number of faces with a lower priority
        int baseOff = count_prio_offset(adjPrio);
        // the furthest faces draw first, and have the highest priority.
        // if two faces have the same distance, the one with the
        // lower id draws first.
        renderPris[baseOff + prioIdx] = distance << 16 | int(~localId & 0xffffu);
    }
}

int tile_height(int z, int x, int y) {
    #define ESCENE_OFFSET 40 // (184-104)/2
    return texelFetch(tileHeightMap, ivec3(x + ESCENE_OFFSET, y + ESCENE_OFFSET, z), 0).r << 3;
}

void hillskew_vertex(inout vec3 v, int hillskew, float modelPosY, int plane) {
    int x = int(v.x);
    int z = int(v.z);
    int px = x & 127;
    int pz = z & 127;
    int sx = x >> 7;
    int sz = z >> 7;
    int h1 = (px * tile_height(plane, sx + 1, sz) + (128 - px) * tile_height(plane, sx, sz)) >> 7;
    int h2 = (px * tile_height(plane, sx + 1, sz + 1) + (128 - px) * tile_height(plane, sx, sz + 1)) >> 7;
    int h3 = (pz * h2 + (128 - pz) * h1) >> 7;
    v.y += h3 - modelPosY;
}

void undoVanillaShading(inout int hsl, vec3 unrotatedNormal) {
    const vec3 LIGHT_DIR_MODEL = vec3(0.57735026, 0.57735026, 0.57735026);
    // subtracts the X lowest lightness levels from the formula.
    // helps keep darker colors appropriately dark
    const int IGNORE_LOW_LIGHTNESS = 3;
    // multiplier applied to vertex' lightness value.
    // results in greater lightening of lighter colors
    const float LIGHTNESS_MULTIPLIER = 3.f;
    // the minimum amount by which each color will be lightened
    const int BASE_LIGHTEN = 10;

    int saturation = hsl >> 7 & 0x7;
    int lightness = hsl & 0x7F;
    float vanillaLightDotNormals = dot(LIGHT_DIR_MODEL, unrotatedNormal);
    if (vanillaLightDotNormals > 0) {
        vanillaLightDotNormals /= length(unrotatedNormal);
        float lighten = max(0, lightness - IGNORE_LOW_LIGHTNESS);
        lightness += int((lighten * LIGHTNESS_MULTIPLIER + BASE_LIGHTEN - lightness) * vanillaLightDotNormals);
    }
    int maxLightness;
    #if LEGACY_GREY_COLORS
    maxLightness = 55;
    #else
    maxLightness = int(127 - 72 * pow(saturation / 7., .05));
    #endif
    lightness = min(lightness, maxLightness);
    hsl &= ~0x7F;
    hsl |= lightness;
}

void sort_and_insert(uint localId, ModelInfo minfo, int thisPriority, int thisDistance, vert thisrvA, vert thisrvB, vert thisrvC) {
    int offset = minfo.offset;
    int size = minfo.size;

    if (localId < size) {
        int outOffset = minfo.idx;
        int uvOffset = minfo.uvOffset;
        int flags = minfo.flags;
        vec3 pos = vec3(minfo.x, minfo.y, minfo.z);
        int orientation = flags & 0x7ff;

        // we only have to order faces against others of the same priority
        const int priorityOffset = count_prio_offset(thisPriority);
        const int numOfPriority = totalMappedNum[thisPriority];
        const int start = priorityOffset; // index of first face with this priority
        const int end = priorityOffset + numOfPriority; // index of last face with this priority
        const int renderPriority = thisDistance << 16 | int(~localId & 0xffffu);
        int myOffset = priorityOffset;

        // calculate position this face will be in
        for (int i = start; i < end; ++i)
            if (renderPriority < renderPris[i])
                ++myOffset;

        // Grab vertex normals from the correct buffer
        vec4 normA = normal[offset + localId * 3    ];
        vec4 normB = normal[offset + localId * 3 + 1];
        vec4 normC = normal[offset + localId * 3 + 2];

        // Rotate normals to match model orientation
        normalout[outOffset + myOffset * 3]     = rotate(normA, orientation);
        normalout[outOffset + myOffset * 3 + 1] = rotate(normB, orientation);
        normalout[outOffset + myOffset * 3 + 2] = rotate(normC, orientation);

        #if UNDO_VANILLA_SHADING
        if ((int(thisrvA.ahsl) >> 20 & 1) == 0) {
            if (length(normA) == 0) {
                // Compute flat normal if necessary, and rotate it back to match unrotated normals
                vec4 N = vec4(cross(thisrvA.pos - thisrvB.pos, thisrvA.pos - thisrvC.pos), 0);
                normA = normB = normC = rotate(N, -orientation);
            }
            undoVanillaShading(thisrvA.ahsl, normA.xyz);
            undoVanillaShading(thisrvB.ahsl, normB.xyz);
            undoVanillaShading(thisrvC.ahsl, normC.xyz);
        }
        #endif

        thisrvA.pos += pos;
        thisrvB.pos += pos;
        thisrvC.pos += pos;

        // apply hillskew
        int plane = (flags >> 24) & 3;
        int hillskew = (flags >> 26) & 1;
        if (hillskew == 1) {
            hillskew_vertex(thisrvA.pos, hillskew, pos.y, plane);
            hillskew_vertex(thisrvB.pos, hillskew, pos.y, plane);
            hillskew_vertex(thisrvC.pos, hillskew, pos.y, plane);
        }

        // position vertices in scene and write to out buffer
        vout[outOffset + myOffset * 3]     = thisrvA;
        vout[outOffset + myOffset * 3 + 1] = thisrvB;
        vout[outOffset + myOffset * 3 + 2] = thisrvC;

        vec4 uvA = vec4(0);
        vec4 uvB = vec4(0);
        vec4 uvC = vec4(0);

        if (uvOffset >= 0) {
            uvA = uv[uvOffset + localId * 3];
            uvB = uv[uvOffset + localId * 3 + 1];
            uvC = uv[uvOffset + localId * 3 + 2];

            if ((int(uvA.w) >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1) {
                // Rotate the texture triangles to match model orientation
                uvA = rotate(uvA, orientation);
                uvB = rotate(uvB, orientation);
                uvC = rotate(uvC, orientation);

                // Shift texture triangles to world space
                uvA.xyz += pos;
                uvB.xyz += pos;
                uvC.xyz += pos;

                // For vanilla UVs, the first 3 components are an integer position vector
                if (hillskew == 1) {
                    hillskew_vertex(uvA.xyz, hillskew, pos.y, plane);
                    hillskew_vertex(uvB.xyz, hillskew, pos.y, plane);
                    hillskew_vertex(uvC.xyz, hillskew, pos.y, plane);
                }
            }
        }

        uvout[outOffset + myOffset * 3]     = uvA;
        uvout[outOffset + myOffset * 3 + 1] = uvB;
        uvout[outOffset + myOffset * 3 + 2] = uvC;
    }
}
