/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
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
#include "constants.cl"
#include "common.cl"

int priority_map(int p, int distance, int _min10, int avg1, int avg2, int avg3);
int count_prio_offset(__local struct shared_data *shared, int priority);
void get_face(
  __local struct shared_data *shared,
  __constant struct UBOCompute *uni,
  __global const struct VertexData *vb,
  uint localId,
  struct ModelInfo minfo,
  /* out */
  int *prio,
  int *dis,
  struct VertexData *o1,
  struct VertexData *o2,
  struct VertexData *o3
);
void add_face_prio_distance(
  __local struct shared_data *shared,
  __constant struct UBOCompute *uni,
  uint localId,
  struct ModelInfo minfo,
  struct VertexData thisrvA,
  struct VertexData thisrvB,
  struct VertexData thisrvC,
  int thisPriority,
  int thisDistance,
  int4 pos
);
int map_face_priority(__local struct shared_data *shared, uint localId, struct ModelInfo minfo, int thisPriority, int thisDistance, int *prio);
void insert_face(__local struct shared_data *shared, uint localId, struct ModelInfo minfo, int adjPrio, int distance, int prioIdx);
int tile_height(read_only image3d_t tileHeightMap, int z, int x, int y);
void hillskew_vertex(read_only image3d_t tileHeightMap, float4 *v, int hillskew, int modelPosY, float modelHeight, int plane);
void undoVanillaShading(struct VertexData *vertex, float3 unrotatedNormal);
float3 applyCharacterDisplacement(
    float3 characterPos,
    float2 vertPos,
    float height,
    float strength,
    float* offsetAccum
);
void applyWindDisplacement(
    __constant struct UBOCompute *uni,
    const struct ObjectWindSample windSample,
    int vertexFlags,
    float modelHeight,
    float3 worldPos,
    float3 vertA, float3 vertB, float3 vertC,
    float3 normA, float3 normB, float3 normC,
    float3* displacementA,
    float3* displacementB,
    float3* displacementC
);
void sort_and_insert(
  __local struct shared_data *shared,
  __global const struct UVData *uv,
  __global const float4 *normal,
  __global struct VertexData *vout,
  __global struct UVData *uvout,
  __global float4 *normalout,
  __constant struct UBOCompute *uni,
  uint localId,
  struct ModelInfo minfo,
  int thisPriority,
  int thisDistance,
  struct VertexData thisrvA,
  struct VertexData thisrvB,
  struct VertexData thisrvC,
  read_only image3d_t tileHeightMap,
  struct ObjectWindSample windSample
);

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
int count_prio_offset(__local struct shared_data *shared, int priority) {
  // this shouldn't ever be outside of (0, 17) because it is the return value from priority_map
  priority = clamp(priority, 0, 17);
  int total = 0;
  for (int i = 0; i < priority; i++) {
    total += shared->totalMappedNum[i];
  }
  return total;
}

void get_face(
  __local struct shared_data *shared,
  __constant struct UBOCompute *uni,
  __global const struct VertexData *vb,
  uint localId,
  struct ModelInfo minfo,
  /* out */
  int *prio,
  int *dis,
  struct VertexData *o1,
  struct VertexData *o2,
  struct VertexData *o3
) {
  uint size = minfo.size;
  uint offset = minfo.offset;
  int flags = minfo.flags;
  uint ssboOffset;

  if (localId < size) {
    ssboOffset = localId;
  } else {
    ssboOffset = 0;
  }

  struct VertexData thisA = vb[offset + ssboOffset * 3];
  struct VertexData thisB = vb[offset + ssboOffset * 3 + 1];
  struct VertexData thisC = vb[offset + ssboOffset * 3 + 2];

  if (localId < size) {
    int orientation = flags & 0x7ff;

    // rotate for model orientation
    float4 thisrvA = rotate_vertex((float4)(thisA.x, thisA.y, thisA.z, 0), orientation);
    float4 thisrvB = rotate_vertex((float4)(thisB.x, thisB.y, thisB.z, 0), orientation);
    float4 thisrvC = rotate_vertex((float4)(thisC.x, thisC.y, thisC.z, 0), orientation);

    // calculate distance to face
    int thisPriority = (thisA.ahsl >> 16) & 0xF;// all vertices on the face have the same priority
    int thisDistance = face_distance(uni, thisrvA, thisrvB, thisrvC);

    *o1 = (struct VertexData){thisrvA.x, thisrvA.y, thisrvA.z, thisA.ahsl};
    *o2 = (struct VertexData){thisrvB.x, thisrvB.y, thisrvB.z, thisB.ahsl};
    *o3 = (struct VertexData){thisrvC.x, thisrvC.y, thisrvC.z, thisC.ahsl};

    *prio = thisPriority;
    *dis = thisDistance;
  } else {
    *o1 = (struct VertexData){0, 0, 0, 0};
    *o2 = (struct VertexData){0, 0, 0, 0};
    *o3 = (struct VertexData){0, 0, 0, 0};
    *prio = 0;
    *dis = 0;
  }
}

void add_face_prio_distance(
  __local struct shared_data *shared,
  __constant struct UBOCompute *uni,
  uint localId,
  struct ModelInfo minfo,
  struct VertexData thisrvA,
  struct VertexData thisrvB,
  struct VertexData thisrvC,
  int thisPriority,
  int thisDistance,
  int4 pos
) {
  uint size = minfo.size;
  if (localId < size) {
    // if the face is not culled, it is calculated into priority distance averages
    float3 posA = (float3)(thisrvA.x, thisrvA.y, thisrvA.z);
    float3 posB = (float3)(thisrvB.x, thisrvB.y, thisrvB.z);
    float3 posC = (float3)(thisrvC.x, thisrvC.y, thisrvC.z);
    if (face_visible(uni, posA, posB, posC, pos)) {
      atomic_add(&shared->totalNum[thisPriority], 1);
      atomic_add(&shared->totalDistance[thisPriority], thisDistance);

      // calculate minimum distance to any face of priority 10 for positioning the 11 faces later
      if (thisPriority == 10) {
        atomic_min(&shared->min10, thisDistance);
      }
    }
  }
}

int map_face_priority(__local struct shared_data *shared, uint localId, struct ModelInfo minfo, int thisPriority, int thisDistance, int *prio) {
  uint size = minfo.size;

  // Compute average distances for 0/2, 3/4, and 6/8

  if (localId < size) {
    int avg1 = 0;
    int avg2 = 0;
    int avg3 = 0;

    if (shared->totalNum[1] > 0 || shared->totalNum[2] > 0) {
      avg1 = (shared->totalDistance[1] + shared->totalDistance[2]) / (shared->totalNum[1] + shared->totalNum[2]);
    }

    if (shared->totalNum[3] > 0 || shared->totalNum[4] > 0) {
      avg2 = (shared->totalDistance[3] + shared->totalDistance[4]) / (shared->totalNum[3] + shared->totalNum[4]);
    }

    if (shared->totalNum[6] > 0 || shared->totalNum[8] > 0) {
      avg3 = (shared->totalDistance[6] + shared->totalDistance[8]) / (shared->totalNum[6] + shared->totalNum[8]);
    }

    int adjPrio = priority_map(thisPriority, thisDistance, shared->min10, avg1, avg2, avg3);
    int prioIdx = atomic_add(&shared->totalMappedNum[adjPrio], 1);

    *prio = adjPrio;
    return prioIdx;
  }

  *prio = 0;
  return 0;
}

void insert_face(__local struct shared_data *shared, uint localId, struct ModelInfo minfo, int adjPrio, int distance, int prioIdx) {
  uint size = minfo.size;

  if (localId < size) {
    // calculate base offset into renderPris based on number of faces with a lower priority
    int baseOff = count_prio_offset(shared, adjPrio);
    // the furthest faces draw first, and have the highest value
    // if two faces have the same distance, the one with the
    // lower id draws first
    shared->renderPris[baseOff + prioIdx] = ((uint)(distance << 16)) | (~localId & 0xffffu);
  }
}

int tile_height(read_only image3d_t tileHeightMap, int z, int x, int y) {
  #define ESCENE_OFFSET 40 // (184-104)/2
  const sampler_t tileHeightSampler = CLK_NORMALIZED_COORDS_FALSE | CLK_ADDRESS_CLAMP_TO_EDGE;
  int4 coord = (int4)(x + ESCENE_OFFSET, y + ESCENE_OFFSET, z, 0);
  return read_imagei(tileHeightMap, tileHeightSampler, coord).x << 3;
}

void hillskew_vertex(read_only image3d_t tileHeightMap, float4 *v, int hillskewMode, int modelPosY, float modelHeight, int plane) {
    // Skip hillskew if in tile-snapping mode and the vertex is too far from the base
    float heightFrac = fabs((*v).y - modelPosY) / modelHeight;
    if (hillskewMode == HILLSKEW_TILE_SNAPPING && heightFrac > HILLSKEW_TILE_SNAPPING_BLEND)
       return;

    float fx = (*v).x;
    float fz = (*v).z;

    float px = fmod(fx, 128.0f);
    float pz = fmod(fz, 128.0f);
    int sx = (int) (floor(fx / 128.0f));
    int sz = (int) (floor(fz / 128.0f));

    float h00 = (float) (tile_height(tileHeightMap, plane, sx,     sz));
    float h10 = (float) (tile_height(tileHeightMap, plane, sx + 1, sz));
    float h01 = (float) (tile_height(tileHeightMap, plane, sx,     sz + 1));
    float h11 = (float) (tile_height(tileHeightMap, plane, sx + 1, sz + 1));

    // Bilinear interpolation
    float hx0 = mix(h00, h10, px / 128.0f);
    float hx1 = mix(h01, h11, px / 128.0f);
    float h = mix(hx0, hx1, pz / 128.0f);

    if ((hillskewMode & HILLSKEW_MODEL) != 0)
        (*v).y += h - modelPosY; // Apply full hillskew

    if ((hillskewMode & HILLSKEW_TILE_SNAPPING) != 0 && heightFrac <= HILLSKEW_TILE_SNAPPING_BLEND) {
        float blend = heightFrac / HILLSKEW_TILE_SNAPPING_BLEND;
        (*v).y = mix(h, (*v).y, blend); // Blend snapping to terrain
    }
}

void undoVanillaShading(struct VertexData *vertex, float3 unrotatedNormal) {
    unrotatedNormal = normalize(unrotatedNormal);

    const float3 LIGHT_DIR_MODEL = (float3)(0.57735026, 0.57735026, 0.57735026);
    // subtracts the X lowest lightness levels from the formula.
    // helps keep darker colors appropriately dark
    const int IGNORE_LOW_LIGHTNESS = 3;
    // multiplier applied to vertex' lightness value.
    // results in greater lightening of lighter colors
    const float LIGHTNESS_MULTIPLIER = 3.f;
    // the minimum amount by which each color will be lightened
    const int BASE_LIGHTEN = 10;

    int hsl = vertex->ahsl;
    int saturation = hsl >> 7 & 0x7;
    int lightness = hsl & 0x7F;
    float vanillaLightDotNormals = dot(LIGHT_DIR_MODEL, unrotatedNormal);
    if (vanillaLightDotNormals > 0) {
        float lighten = max(0, lightness - IGNORE_LOW_LIGHTNESS);
        lightness += (int) ((lighten * LIGHTNESS_MULTIPLIER + BASE_LIGHTEN - lightness) * vanillaLightDotNormals);
    }
    int maxLightness;
    #if LEGACY_GREY_COLORS
        maxLightness = 55;
    #else
        const int MAX_BRIGHTNESS_LOOKUP_TABLE[8] = { 127, 61, 59, 57, 56, 56, 55, 55 };
        maxLightness = MAX_BRIGHTNESS_LOOKUP_TABLE[saturation];
    #endif
    lightness = min(lightness, maxLightness);
    hsl &= ~0x7F;
    hsl |= lightness;
    vertex->ahsl = hsl;
}

float3 applyCharacterDisplacement(
    float3 characterPos,
    float2 vertPos,
    float height,
    float strength,
    float* offsetAccum
) {
    float2 offset = vertPos - characterPos.xy;
    float offsetLen = fabs(length(offset));

    if (offsetLen >= fabs(characterPos.z))
        return (float3)(0.0f, 0.0f, 0.0f);

    float offsetFrac = clamp(1.0f - (offsetLen / fabs(characterPos.z)), 0.0f, 1.0f);
    float displacementFrac = offsetFrac * offsetFrac;

    float3 horizontalDisplacement = normalize((float3)(offset.x, 0.0f, offset.y)) * (height * strength * displacementFrac * 0.5f);
    float3 verticalDisplacement = (float3)(0.0f, height * strength * displacementFrac, 0.0f);

    *offsetAccum += offsetFrac;

    return mix(horizontalDisplacement, verticalDisplacement, offsetFrac);
}

float getModelWindDisplacementMod(int vertexFlags) {
    const float modifiers[7] = { 0.25f, 0.5f, 0.7f, 1.0f, 1.25f, 1.5f, 2.0f };
    int modifierIDx = (vertexFlags >> MATERIAL_FLAG_WIND_MODIFIER) & 0x7;
    return modifiers[modifierIDx];
}

void applyWindDisplacement(
    __constant struct UBOCompute *uni,
    const struct ObjectWindSample windSample,
    int vertexFlags,
    float modelHeight,
    float3 worldPos,
    float3 vertA, float3 vertB, float3 vertC,
    float3 normA, float3 normB, float3 normC,
    float3* displacementA,
    float3* displacementB,
    float3* displacementC
) {
#if WIND_DISPLACEMENT || CHARACTER_DISPLACEMENT
    const int windDisplacementMode = (vertexFlags >> MATERIAL_FLAG_WIND_SWAYING) & 0x7;
    if (windDisplacementMode <= WIND_DISPLACEMENT_DISABLED)
        return;

    float strengthA = saturate(fabs(vertA.y) / modelHeight);
    float strengthB = saturate(fabs(vertB.y) / modelHeight);
    float strengthC = saturate(fabs(vertC.y) / modelHeight);

    if ((vertexFlags >> MATERIAL_FLAG_INVERT_DISPLACEMENT_STRENGTH & 1) == 1) {
        strengthA = 1.0f - strengthA;
        strengthB = 1.0f - strengthB;
        strengthC = 1.0f - strengthC;
    }

    float modelDisplacementMod = getModelWindDisplacementMod(vertexFlags);
#if WIND_DISPLACEMENT
    if (windDisplacementMode >= WIND_DISPLACEMENT_VERTEX) {
        const float VertexSnapping = 150.0f;
        const float VertexDisplacementMod = 0.2f;

        float2 snappedA = (floor(vertA.xz / VertexSnapping + 0.5f) * VertexSnapping);
        float2 snappedB = (floor(vertB.xz / VertexSnapping + 0.5f) * VertexSnapping);
        float2 snappedC = (floor(vertC.xz / VertexSnapping + 0.5f) * VertexSnapping);

        float windNoiseA = mix(-0.5f, 0.5f, noise((snappedA + (float2)(uni->windOffset, 0.0f)) * WIND_DISPLACEMENT_NOISE_RESOLUTION));
        float windNoiseB = mix(-0.5f, 0.5f, noise((snappedB + (float2)(uni->windOffset, 0.0f)) * WIND_DISPLACEMENT_NOISE_RESOLUTION));
        float windNoiseC = mix(-0.5f, 0.5f, noise((snappedC + (float2)(uni->windOffset, 0.0f)) * WIND_DISPLACEMENT_NOISE_RESOLUTION));

        if (windDisplacementMode == WIND_DISPLACEMENT_VERTEX_WITH_HEMISPHERE_BLEND) {
            const float minDist = 50.0f;
            const float blendDist = 10.0f;

            float distBlendA = clamp(((fabs(vertA.x) + fabs(vertA.z)) - minDist) / blendDist, 0.0f, 1.0f);
            float distBlendB = clamp(((fabs(vertB.x) + fabs(vertB.z)) - minDist) / blendDist, 0.0f, 1.0f);
            float distBlendC = clamp(((fabs(vertC.x) + fabs(vertC.z)) - minDist) / blendDist, 0.0f, 1.0f);

            float heightFadeA = clamp((strengthA - 0.5f) / 0.2f, 0.0f, 1.0f);
            float heightFadeB = clamp((strengthB - 0.5f) / 0.2f, 0.0f, 1.0f);
            float heightFadeC = clamp((strengthC - 0.5f) / 0.2f, 0.0f, 1.0f);

            strengthA *= (strengthA >= 0.3f ?  mix(distBlendA, 1.0f, heightFadeA) : 0.0f);
            strengthB *= (strengthB >= 0.3f ? mix(distBlendB, 1.0f, heightFadeB) : 0.0f);
            strengthC *= (strengthC >= 0.3f ? mix(distBlendC, 1.0f, heightFadeC) : 0.0f);
        } else if (windDisplacementMode == WIND_DISPLACEMENT_VERTEX_JIGGLE) {
            float3 skewA = normalize(cross(normA, (float3)(0.0f, 1.0f, 0.0f)));
            float3 skewB = normalize(cross(normB, (float3)(0.0f, 1.0f, 0.0f)));
            float3 skewC = normalize(cross(normC, (float3)(0.0f, 1.0f, 0.0f)));

            strengthA *= modelDisplacementMod;
            strengthB *= modelDisplacementMod;
            strengthC *= modelDisplacementMod;

            *displacementA = windNoiseA * (windSample.heightBasedStrength * strengthA) * 0.5f * skewA;
            *displacementB = windNoiseB * (windSample.heightBasedStrength * strengthB) * 0.5f * skewB;
            *displacementC = windNoiseC * (windSample.heightBasedStrength * strengthC) * 0.5f * skewC;

            skewA = normalize(cross(normA, (float3)(1.0f, 0.0f, 0.0f)));
            skewB = normalize(cross(normB, (float3)(1.0f, 0.0f, 0.0f)));
            skewC = normalize(cross(normC, (float3)(1.0f, 0.0f, 0.0f)));

            *displacementA += (1.0f - windNoiseA) * (windSample.heightBasedStrength * strengthA) * 0.5f * skewA;
            *displacementB += (1.0f - windNoiseB) * (windSample.heightBasedStrength * strengthB) * 0.5f * skewB;
            *displacementC += (1.0f - windNoiseC) * (windSample.heightBasedStrength * strengthC) * 0.5f * skewC;
        } else {
            *displacementA = windNoiseA * (windSample.heightBasedStrength * strengthA * VertexDisplacementMod) * windSample.direction;
            *displacementB = windNoiseB * (windSample.heightBasedStrength * strengthB * VertexDisplacementMod) * windSample.direction;
            *displacementC = windNoiseC * (windSample.heightBasedStrength * strengthC * VertexDisplacementMod) * windSample.direction;

            strengthA = clamp(strengthA - VertexDisplacementMod, 0.0f, 1.0f) * modelDisplacementMod;
            strengthB = clamp(strengthB - VertexDisplacementMod, 0.0f, 1.0f) * modelDisplacementMod;
            strengthC = clamp(strengthC - VertexDisplacementMod, 0.0f, 1.0f) * modelDisplacementMod;
        }
    }

    if (windDisplacementMode != WIND_DISPLACEMENT_VERTEX_JIGGLE) {
        *displacementA += windSample.displacement * strengthA * modelDisplacementMod;
        *displacementB += windSample.displacement * strengthB * modelDisplacementMod;
        *displacementC += windSample.displacement * strengthC * modelDisplacementMod;
    }
#endif // WIND_DISPLACEMENT

#if CHARACTER_DISPLACEMENT
    if (windDisplacementMode == WIND_DISPLACEMENT_OBJECT) {
        float2 worldVertA = (worldPos + vertA).xz;
        float2 worldVertB = (worldPos + vertB).xz;
        float2 worldVertC = (worldPos + vertC).xz;

        float fractAccum = 0.0f;
        for (int i = 0; i < uni->characterPositionCount; i++) {
            *displacementA += applyCharacterDisplacement(uni->characterPositions[i], worldVertA, modelHeight, strengthA, &fractAccum);
            *displacementB += applyCharacterDisplacement(uni->characterPositions[i], worldVertB, modelHeight, strengthB, &fractAccum);
            *displacementC += applyCharacterDisplacement(uni->characterPositions[i], worldVertC, modelHeight, strengthC, &fractAccum);
            if (fractAccum >= 2.0f) break;
        }
    }
#endif

#endif // WIND_DISPLACEMENT || CHARACTER_DISPLACEMENT
}

void sort_and_insert(
  __local struct shared_data *shared,
  __global const struct UVData *uv,
  __global const float4 *normal,
  __global struct VertexData *vout,
  __global struct UVData *uvout,
  __global float4 *normalout,
  __constant struct UBOCompute *uni,
  uint localId,
  struct ModelInfo minfo,
  int thisPriority,
  int thisDistance,
  struct VertexData thisrvA,
  struct VertexData thisrvB,
  struct VertexData thisrvC,
  read_only image3d_t tileHeightMap,
  struct ObjectWindSample windSample
) {
  uint offset = minfo.offset;
  uint size = minfo.size;

  if (localId < size) {
    int outOffset = minfo.idx;
    int uvOffset = minfo.uvOffset;
    int flags = minfo.flags;
    int modelY = minfo.y >> 16;
    float4 pos = (float4)(minfo.x, modelY, minfo.z, 0);
    float modelHeight = (float) (minfo.y & 0xffff);
    int orientation = minfo.flags & 0x7ff;
    int vertexFlags = uvOffset >= 0 ? uv[uvOffset + localId * 3].materialData : 0;

    // we only have to order faces against others of the same priority
    const int priorityOffset = count_prio_offset(shared, thisPriority);
    const int numOfPriority = shared->totalMappedNum[thisPriority];
    const int start = priorityOffset;                // index of first face with this priority
    const int end = priorityOffset + numOfPriority;  // index of last face with this priority
    const int renderPriority = thisDistance << 16 | (int)(~localId & 0xffffu);
    int myOffset = priorityOffset;

    // calculate position this face will be in
    for (int i = start; i < end; ++i) {
      if (renderPriority < shared->renderPris[i]) {
        ++myOffset;
      }
    }

    float4 normA = normal[offset + localId * 3];
    float4 normB = normal[offset + localId * 3 + 1];
    float4 normC = normal[offset + localId * 3 + 2];

    float4 vertA = (float4)(thisrvA.x, thisrvA.y, thisrvA.z, 0);
    float4 vertB = (float4)(thisrvB.x, thisrvB.y, thisrvB.z, 0);
    float4 vertC = (float4)(thisrvC.x, thisrvC.y, thisrvC.z, 0);

    float3 displacementA = (float3)(0);
    float3 displacementB = (float3)(0);
    float3 displacementC = (float3)(0);

    applyWindDisplacement(uni, windSample, vertexFlags, modelHeight, pos.xyz,
        vertA.xyz, vertB.xyz, vertC.xyz,
        normA.xyz, normB.xyz, normC.xyz,
        &displacementA, &displacementB, &displacementC);

    vertA += pos + (float4)(displacementA, 0.0);
    vertB += pos + (float4)(displacementB, 0.0);
    vertC += pos + (float4)(displacementC, 0.0);

    #if UNDO_VANILLA_SHADING
        if ((thisrvA.ahsl >> 20 & 1) == 0) {
            if (fast_length(normA) == 0) {
                // Compute flat normal if necessary, and rotate it back to match unrotated normals
                float3 N = cross(
                  (float3)(thisrvA.x - thisrvB.x, thisrvA.y - thisrvB.y, thisrvA.z - thisrvB.z),
                  (float3)(thisrvA.x - thisrvC.x, thisrvA.y - thisrvC.y, thisrvA.z - thisrvC.z)
                );
                normA = normB = normC = (float4) (N, 1.f);
            }
            undoVanillaShading(&thisrvA, normA.xyz);
            undoVanillaShading(&thisrvB, normB.xyz);
            undoVanillaShading(&thisrvC, normC.xyz);
        }
    #endif

    normalout[outOffset + myOffset * 3    ] = rotate_vertex(normA, orientation);
    normalout[outOffset + myOffset * 3 + 1] = rotate_vertex(normB, orientation);
    normalout[outOffset + myOffset * 3 + 2] = rotate_vertex(normC, orientation);


    // apply hillskew
    int plane = flags >> 24 & 3;
    int hillskewFlags = flags >> 26 & 1;
    if ((vertexFlags >> MATERIAL_FLAG_TERRAIN_VERTEX_SNAPPING & 1) == 1)
        hillskewFlags |= HILLSKEW_TILE_SNAPPING;
    if (hillskewFlags != HILLSKEW_NONE) {
        hillskew_vertex(tileHeightMap, &vertA, hillskewFlags, modelY, modelHeight, plane);
        hillskew_vertex(tileHeightMap, &vertB, hillskewFlags, modelY, modelHeight, plane);
        hillskew_vertex(tileHeightMap, &vertC, hillskewFlags, modelY, modelHeight, plane);
    }

    // position vertices in scene and write to out buffer
    vout[outOffset + myOffset * 3] = (struct VertexData){vertA.x, vertA.y, vertA.z, thisrvA.ahsl};
    vout[outOffset + myOffset * 3 + 1] = (struct VertexData){vertB.x, vertB.y, vertB.z, thisrvB.ahsl};
    vout[outOffset + myOffset * 3 + 2] = (struct VertexData){vertC.x, vertC.y, vertC.z, thisrvC.ahsl};

    struct UVData uvA = (struct UVData){0.0, 0.0, 0.0, 0};
    struct UVData uvB = (struct UVData){0.0, 0.0, 0.0, 0};
    struct UVData uvC = (struct UVData){0.0, 0.0, 0.0, 0};

    if (uvOffset >= 0) {
      uvA = uv[uvOffset + localId * 3];
      uvB = uv[uvOffset + localId * 3 + 1];
      uvC = uv[uvOffset + localId * 3 + 2];

      float4 uvwA = (float4)(uvA.u, uvA.v, uvA.w, 0.0);
      float4 uvwB = (float4)(uvB.u, uvB.v, uvB.w, 0.0);
      float4 uvwC = (float4)(uvC.u, uvC.v, uvC.w, 0.0);

      if ((vertexFlags >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1) {
        // Rotate the texture triangles to match model orientation
        uvwA = rotate_vertex(uvwA, orientation);
        uvwB = rotate_vertex(uvwB, orientation);
        uvwC = rotate_vertex(uvwC, orientation);

        // Shift texture triangles to world space
        float3 modelPos = convert_float3(pos.xyz);
        uvwA.xyz += modelPos + displacementA.xyz;
        uvwB.xyz += modelPos + displacementB.xyz;
        uvwC.xyz += modelPos + displacementC.xyz;

        // For vanilla UVs, the first 3 components are an integer position vector
        if (hillskewFlags != HILLSKEW_NONE) {
            hillskew_vertex(tileHeightMap, &uvwA, hillskewFlags, modelY, modelHeight, plane);
            hillskew_vertex(tileHeightMap, &uvwB, hillskewFlags, modelY, modelHeight, plane);
            hillskew_vertex(tileHeightMap, &uvwC, hillskewFlags, modelY, modelHeight, plane);
        }
      }

      uvA = (struct UVData){uvwA.x, uvwA.y, uvwA.z, uvA.materialData};
      uvB = (struct UVData){uvwB.x, uvwB.y, uvwB.z, uvA.materialData};
      uvC = (struct UVData){uvwC.x, uvwC.y, uvwC.z, uvA.materialData};
    }

    uvout[outOffset + myOffset * 3]     = uvA;
    uvout[outOffset + myOffset * 3 + 1] = uvB;
    uvout[outOffset + myOffset * 3 + 2] = uvC;
  }
}
