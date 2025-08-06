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
#include <uniforms/compute.glsl>

#include <utils/constants.glsl>

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

void add_face_prio_distance(const uint localId, const ModelInfo minfo, out int prio, out int dis) {
    if (localId < minfo.size) {
        int offset = minfo.offset;
        int flags = minfo.flags;

        int orientation = flags & 0x7ff;

        // Grab triangle vertices from the correct buffer
        VertexData thisA = vb[offset + localId * 3];
        VertexData thisB = vb[offset + localId * 3 + 1];
        VertexData thisC = vb[offset + localId * 3 + 2];

        // rotate for model orientation
        thisA.pos = rotate(thisA.pos, orientation);
        thisB.pos = rotate(thisB.pos, orientation);
        thisC.pos = rotate(thisC.pos, orientation);

        // calculate distance to face
        prio = (thisA.ahsl >> 16) & 0xF;// all vertices on the face have the same priority
        dis = face_distance(thisA.pos, thisB.pos, thisC.pos);

        // if the face is not culled, it is calculated into priority distance averages
        vec3 modelPos = vec3(minfo.x, minfo.y >> 16, minfo.z);
        if (face_visible(thisA.pos, thisB.pos, thisC.pos, modelPos)) {
            atomicAdd(totalNum[prio], 1);
            atomicAdd(totalDistance[prio], dis);

            // calculate minimum distance to any face of priority 10 for positioning the 11 faces later
            if (prio == 10) {
                atomicMin(min10, dis);
            }
        }
    }
}

int map_face_priority(uint localId, const ModelInfo minfo, int thisPriority, int thisDistance, out int prio) {
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

void insert_face(uint localId, const ModelInfo minfo, int adjPrio, int distance, int prioIdx) {
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

void hillskew_vertex(inout vec3 v, int hillskewMode, float modelPosY, float modelHeight, int plane) {
    // Skip hillskew if in tile-snapping mode and the vertex is too far from the base
    float heightFrac = abs(v.y - modelPosY) / modelHeight;
    if (hillskewMode == HILLSKEW_TILE_SNAPPING && heightFrac > HILLSKEW_TILE_SNAPPING_BLEND)
        return;

    float fx = v.x;
    float fz = v.z;

    float px = mod(fx, 128.0);
    float pz = mod(fz, 128.0);
    int sx = int(floor(fx / 128.0));
    int sz = int(floor(fz / 128.0));

    float h00 = float(tile_height(plane, sx,     sz));
    float h10 = float(tile_height(plane, sx + 1, sz));
    float h01 = float(tile_height(plane, sx,     sz + 1));
    float h11 = float(tile_height(plane, sx + 1, sz + 1));

    // Bilinear interpolation
    float hx0 = mix(h00, h10, px / 128.0);
    float hx1 = mix(h01, h11, px / 128.0);
    float h = mix(hx0, hx1, pz / 128.0);

    if ((hillskewMode & HILLSKEW_MODEL) != 0)
        v.y += h - modelPosY; // Apply full hillskew

    if ((hillskewMode & HILLSKEW_TILE_SNAPPING) != 0 && heightFrac <= HILLSKEW_TILE_SNAPPING_BLEND) {
        float blend = heightFrac / HILLSKEW_TILE_SNAPPING_BLEND;
        v.y = mix(h, v.y, blend); // Blend snapping to terrain
    }
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

vec3 applyCharacterDisplacement(vec3 characterPos, vec2 vertPos, float height, float strength, inout float offsetAccum) {
    vec2 offset = vertPos - characterPos.xy;
    float offsetLen = length(offset);

    if (offsetLen >= characterPos.z)
        return vec3(0);

    float offsetFrac = saturate(1.0 - (offsetLen / characterPos.z));
    float displacementFrac = offsetFrac * offsetFrac;

    vec3 horizontalDisplacement = normalize(vec3(offset.x, 0.0, offset.y)) * (height * strength * displacementFrac * 0.5);
    vec3 verticalDisplacement = vec3(0.0, height * strength * displacementFrac, 0.0);

    offsetAccum += offsetFrac;

    return mix(horizontalDisplacement, verticalDisplacement, offsetFrac);
}

float getModelWindDisplacementMod(int vertexFlags) {
    const float modifiers[7] = { 0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0};
    int modifierIDx = (vertexFlags >> MATERIAL_FLAG_WIND_MODIFIER) & 0x7;
    return modifiers[modifierIDx];
}

void applyWindDisplacement(const ObjectWindSample windSample, int vertexFlags, float modelHeight, vec3 worldPos,
    in vec3 vertA, in vec3 vertB, in vec3 vertC,
    in vec3 normA, in vec3 normB, in vec3 normC,
    inout vec3 displacementA, inout vec3 displacementB, inout vec3 displacementC
) {
    int windDisplacementMode = (vertexFlags >> MATERIAL_FLAG_WIND_SWAYING) & 0x7;
    if (windDisplacementMode <= WIND_DISPLACEMENT_DISABLED)
        return;

    float strengthA = saturate(abs(vertA.y) / modelHeight);
    float strengthB = saturate(abs(vertB.y) / modelHeight);
    float strengthC = saturate(abs(vertC.y) / modelHeight);

    if ((vertexFlags >> MATERIAL_FLAG_INVERT_DISPLACEMENT_STRENGTH & 1) == 1) {
        strengthA = 1.0 - strengthA;
        strengthB = 1.0 - strengthB;
        strengthC = 1.0 - strengthC;
    }

    float modelDisplacementMod = getModelWindDisplacementMod(vertexFlags);
#if WIND_DISPLACEMENT
    if (windDisplacementMode >= WIND_DISPLACEMENT_VERTEX) {
        const float VertexSnapping = 150.0; // Snap so vertices which are almost overlapping will obtain the same noise value
        const float VertexDisplacementMod = 0.2; // Avoid over stretching which can cause issues in ComputeUVs
        float windNoiseA = mix(-0.5, 0.5, noise((snap(vertA, VertexSnapping).xz + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION));
        float windNoiseB = mix(-0.5, 0.5, noise((snap(vertB, VertexSnapping).xz + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION));
        float windNoiseC = mix(-0.5, 0.5, noise((snap(vertC, VertexSnapping).xz + vec2(windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION));

        if (windDisplacementMode == WIND_DISPLACEMENT_VERTEX_WITH_HEMISPHERE_BLEND) {
            const float minDist = 50;
            const float blendDist = 10.0;

            float distBlendA = saturate(((abs(vertA.x) + abs(vertA.z)) - minDist) / blendDist);
            float distBlendB = saturate(((abs(vertB.x) + abs(vertB.z)) - minDist) / blendDist);
            float distBlendC = saturate(((abs(vertC.x) + abs(vertC.z)) - minDist) / blendDist);

            float heightFadeA = saturate((strengthA - 0.5) / 0.2);
            float heightFadeB = saturate((strengthB - 0.5) / 0.2);
            float heightFadeC = saturate((strengthC - 0.5) / 0.2);

            strengthA *= mix(0.0, mix(distBlendA, 1.0, heightFadeA), step(0.3, strengthA));
            strengthB *= mix(0.0, mix(distBlendB, 1.0, heightFadeB), step(0.3, strengthB));
            strengthC *= mix(0.0, mix(distBlendC, 1.0, heightFadeC), step(0.3, strengthC));
        } else {
            if (windDisplacementMode == WIND_DISPLACEMENT_VERTEX_JIGGLE) {
                vec3 vertASkew = safe_normalize(cross(normA.xyz, vec3(0, 1, 0)));
                vec3 vertBSkew = safe_normalize(cross(normB.xyz, vec3(0, 1, 0)));
                vec3 vertCSkew = safe_normalize(cross(normC.xyz, vec3(0, 1, 0)));

                strengthA *= modelDisplacementMod;
                strengthB *= modelDisplacementMod;
                strengthC *= modelDisplacementMod;

                displacementA = ((windNoiseA * (windSample.heightBasedStrength * strengthA) * 0.5) * vertASkew);
                displacementB = ((windNoiseB * (windSample.heightBasedStrength * strengthB) * 0.5) * vertBSkew);
                displacementC = ((windNoiseC * (windSample.heightBasedStrength * strengthC) * 0.5) * vertCSkew);

                vertASkew = safe_normalize(cross(normA.xyz, vec3(1, 0, 0)));
                vertBSkew = safe_normalize(cross(normB.xyz, vec3(1, 0, 0)));
                vertCSkew = safe_normalize(cross(normC.xyz, vec3(1, 0, 0)));

                displacementA += (((1.0 - windNoiseA) * (windSample.heightBasedStrength * strengthA) * 0.5) * vertASkew);
                displacementB += (((1.0 - windNoiseB) * (windSample.heightBasedStrength * strengthB) * 0.5) * vertBSkew);
                displacementC += (((1.0 - windNoiseC) * (windSample.heightBasedStrength * strengthC) * 0.5) * vertCSkew);
            } else {
                displacementA = ((windNoiseA * (windSample.heightBasedStrength * strengthA * VertexDisplacementMod)) * windSample.direction);
                displacementB = ((windNoiseB * (windSample.heightBasedStrength * strengthB * VertexDisplacementMod)) * windSample.direction);
                displacementC = ((windNoiseC * (windSample.heightBasedStrength * strengthC * VertexDisplacementMod)) * windSample.direction);

                strengthA = saturate(strengthA - VertexDisplacementMod) * modelDisplacementMod;
                strengthB = saturate(strengthB - VertexDisplacementMod) * modelDisplacementMod;
                strengthC = saturate(strengthC - VertexDisplacementMod) * modelDisplacementMod;
            }
        }
    }

    if (windDisplacementMode != WIND_DISPLACEMENT_VERTEX_JIGGLE) {
        // Object Displacement
        displacementA += windSample.displacement * strengthA * modelDisplacementMod;
        displacementB += windSample.displacement * strengthB * modelDisplacementMod;
        displacementC += windSample.displacement * strengthC * modelDisplacementMod;
    }
#endif

#if CHARACTER_DISPLACEMENT
    if (windDisplacementMode == WIND_DISPLACEMENT_OBJECT) {
        vec2 worldVertA = (worldPos + vertA).xz;
        vec2 worldVertB = (worldPos + vertB).xz;
        vec2 worldVertC = (worldPos + vertC).xz;

        float fractAccum = 0.0;
        for (int i = 0; i < characterPositionCount; i++) {
            displacementA += applyCharacterDisplacement(characterPositions[i], worldVertA, modelHeight, strengthA, fractAccum);
            displacementB += applyCharacterDisplacement(characterPositions[i], worldVertB, modelHeight, strengthB, fractAccum);
            displacementC += applyCharacterDisplacement(characterPositions[i], worldVertC, modelHeight, strengthC, fractAccum);
            if (fractAccum >= 2.0)
                break;
        }
    }
#endif
}

void sort_and_insert(uint localId, const ModelInfo minfo, int thisPriority, int thisDistance, ObjectWindSample windSample) {
    int offset = minfo.offset;
    int size = minfo.size;

    if (localId < size) {
        int outOffset = minfo.idx;
        int uvOffset = minfo.uvOffset;
        int flags = minfo.flags;
        vec3 modelPos = vec3(minfo.x, minfo.y >> 16, minfo.z);
        float height = minfo.y & 0xffff;
        int orientation = flags & 0x7ff;
        int vertexFlags = uvOffset >= 0 ? uv[uvOffset + localId * 3].materialFlags : 0;

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

        vec3 displacementA = vec3(0);
        vec3 displacementB = vec3(0);
        vec3 displacementC = vec3(0);

        // Grab triangle vertices from the correct buffer
        VertexData thisrvA = vb[offset + localId * 3];
        VertexData thisrvB = vb[offset + localId * 3 + 1];
        VertexData thisrvC = vb[offset + localId * 3 + 2];

        // Grab vertex normals from the correct buffer
        vec4 normA = normal[offset + localId * 3    ];
        vec4 normB = normal[offset + localId * 3 + 1];
        vec4 normC = normal[offset + localId * 3 + 2];

        applyWindDisplacement(windSample, vertexFlags, height, modelPos,
            thisrvA.pos, thisrvB.pos, thisrvC.pos,
            normA.xyz, normB.xyz, normC.xyz,
            displacementA, displacementB, displacementC);

        // Rotate normals to match model orientation
        normalout[outOffset + myOffset * 3]     = rotate(normA, orientation);
        normalout[outOffset + myOffset * 3 + 1] = rotate(normB, orientation);
        normalout[outOffset + myOffset * 3 + 2] = rotate(normC, orientation);

        // Apply any displacement
        thisrvA.pos += displacementA;
        thisrvB.pos += displacementB;
        thisrvC.pos += displacementC;

        // rotate for model orientation
        thisrvA.pos = rotate(thisrvA.pos, orientation);
        thisrvB.pos = rotate(thisrvB.pos, orientation);
        thisrvC.pos = rotate(thisrvC.pos, orientation);

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

        thisrvA.pos += modelPos;
        thisrvB.pos += modelPos;
        thisrvC.pos += modelPos;

        // apply hillskew
        int plane = flags >> 24 & 3;
        int hillskewFlags = flags >> 26 & 1;
        if ((vertexFlags >> MATERIAL_FLAG_TERRAIN_VERTEX_SNAPPING & 1) == 1)
            hillskewFlags |= HILLSKEW_TILE_SNAPPING;
        if (hillskewFlags != HILLSKEW_NONE) {
            hillskew_vertex(thisrvA.pos, hillskewFlags, modelPos.y, height, plane);
            hillskew_vertex(thisrvB.pos, hillskewFlags, modelPos.y, height, plane);
            hillskew_vertex(thisrvC.pos, hillskewFlags, modelPos.y, height, plane);
        }

        // position vertices in scene and write to out buffer
        vout[outOffset + myOffset * 3]     = thisrvA;
        vout[outOffset + myOffset * 3 + 1] = thisrvB;
        vout[outOffset + myOffset * 3 + 2] = thisrvC;

        UVData uvA = UVData(vec3(0.0), 0);
        UVData uvB = UVData(vec3(0.0), 0);
        UVData uvC = UVData(vec3(0.0), 0);

        if (uvOffset >= 0) {
            uvA = uv[uvOffset + localId * 3];
            uvB = uv[uvOffset + localId * 3 + 1];
            uvC = uv[uvOffset + localId * 3 + 2];

            if ((vertexFlags >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1) {
                uvA.uvw += displacementA;
                uvB.uvw += displacementB;
                uvC.uvw += displacementC;

                // Rotate the texture triangles to match model orientation
                uvA.uvw = rotate(uvA.uvw, orientation);
                uvB.uvw = rotate(uvB.uvw, orientation);
                uvC.uvw = rotate(uvC.uvw, orientation);

                // Shift texture triangles to world space
                uvA.uvw += modelPos;
                uvB.uvw += modelPos;
                uvC.uvw += modelPos;

                // For vanilla UVs, the first 3 components are an integer position vector
                if (hillskewFlags != HILLSKEW_NONE) {
                    hillskew_vertex(uvA.uvw, hillskewFlags, modelPos.y, height, plane);
                    hillskew_vertex(uvB.uvw, hillskewFlags, modelPos.y, height, plane);
                    hillskew_vertex(uvC.uvw, hillskewFlags, modelPos.y, height, plane);
                }
            }
        }

        uvout[outOffset + myOffset * 3]     = uvA;
        uvout[outOffset + myOffset * 3 + 1] = uvB;
        uvout[outOffset + myOffset * 3 + 2] = uvC;
    }
}
