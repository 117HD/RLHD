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

#include constants.cl
#include vanilla_uvs.cl

int priority_map(int p, int distance, int _min10, int avg1, int avg2, int avg3);
int count_prio_offset(__local struct shared_data *shared, int priority);
void get_face(
  __local struct shared_data *shared,
  __constant struct uniform *uni,
  __global const struct vert *vb,
  uint localId,
  struct ModelInfo minfo,
  /* out */
  int *prio,
  int *dis,
  struct vert *o1,
  struct vert *o2,
  struct vert *o3
);
void add_face_prio_distance(
  __local struct shared_data *shared,
  __constant struct uniform *uni,
  uint localId,
  struct ModelInfo minfo,
  struct vert thisrvA,
  struct vert thisrvB,
  struct vert thisrvC,
  int thisPriority,
  int thisDistance,
  int4 pos
);
int map_face_priority(__local struct shared_data *shared, uint localId, struct ModelInfo minfo, int thisPriority, int thisDistance, int *prio);
void insert_face(__local struct shared_data *shared, uint localId, struct ModelInfo minfo, int adjPrio, int distance, int prioIdx);
int tile_height(read_only image3d_t tileHeightMap, int z, int x, int y);
void hillskew_vertex(read_only image3d_t tileHeightMap, float4 *v, int hillskew, int y, int plane);
void undoVanillaShading(struct vert *vertex, float3 unrotatedNormal);
void sort_and_insert(
  __local struct shared_data *shared,
  __global const float4 *uv,
  __global const float4 *normal,
  __global struct vert *vout,
  __global float4 *uvout,
  __global float4 *normalout,
  __constant struct uniform *uni,
  uint localId,
  struct ModelInfo minfo,
  int thisPriority,
  int thisDistance,
  float3 windDirection,
  struct vert thisrvA,
  struct vert thisrvB,
  struct vert thisrvC,
  read_only image3d_t tileHeightMap
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
  __constant struct uniform *uni,
  __global const struct vert *vb,
  uint localId,
  struct ModelInfo minfo,
  /* out */
  int *prio,
  int *dis,
  struct vert *o1,
  struct vert *o2,
  struct vert *o3
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

  struct vert thisA = vb[offset + ssboOffset * 3];
  struct vert thisB = vb[offset + ssboOffset * 3 + 1];
  struct vert thisC = vb[offset + ssboOffset * 3 + 2];

  if (localId < size) {
    int orientation = flags & 0x7ff;

    // rotate for model orientation
    float4 thisrvA = rotate_vertex((float4)(thisA.x, thisA.y, thisA.z, 0), orientation);
    float4 thisrvB = rotate_vertex((float4)(thisB.x, thisB.y, thisB.z, 0), orientation);
    float4 thisrvC = rotate_vertex((float4)(thisC.x, thisC.y, thisC.z, 0), orientation);

    // calculate distance to face
    int thisPriority = (thisA.ahsl >> 16) & 0xF;// all vertices on the face have the same priority
    int thisDistance = face_distance(uni, thisrvA, thisrvB, thisrvC);

    *o1 = (struct vert){thisrvA.x, thisrvA.y, thisrvA.z, thisA.ahsl};
    *o2 = (struct vert){thisrvB.x, thisrvB.y, thisrvB.z, thisB.ahsl};
    *o3 = (struct vert){thisrvC.x, thisrvC.y, thisrvC.z, thisC.ahsl};

    *prio = thisPriority;
    *dis = thisDistance;
  } else {
    *o1 = (struct vert){0, 0, 0, 0};
    *o2 = (struct vert){0, 0, 0, 0};
    *o3 = (struct vert){0, 0, 0, 0};
    *prio = 0;
    *dis = 0;
  }
}

void add_face_prio_distance(
  __local struct shared_data *shared,
  __constant struct uniform *uni,
  uint localId,
  struct ModelInfo minfo,
  struct vert thisrvA,
  struct vert thisrvB,
  struct vert thisrvC,
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

void hillskew_vertex(read_only image3d_t tileHeightMap, float4 *v, int hillskew, int y, int plane) {
    int x = (int) (*v).x;
    int z = (int) (*v).z;
    int px = x & 127;
    int pz = z & 127;
    int sx = x >> 7;
    int sz = z >> 7;
    int h1 = (px * tile_height(tileHeightMap, plane, sx + 1, sz) + (128 - px) * tile_height(tileHeightMap, plane, sx, sz)) >> 7;
    int h2 = (px * tile_height(tileHeightMap, plane, sx + 1, sz + 1) + (128 - px) * tile_height(tileHeightMap, plane, sx, sz + 1)) >> 7;
    int h3 = (pz * h2 + (128 - pz) * h1) >> 7;
    (*v).y += h3 - y;
}

void undoVanillaShading(struct vert *vertex, float3 unrotatedNormal) {
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

void sort_and_insert(
  __local struct shared_data *shared,
  __global const float4 *uv,
  __global const float4 *normal,
  __global struct vert *vout,
  __global float4 *uvout,
  __global float4 *normalout,
  __constant struct uniform *uni,
  uint localId,
  struct ModelInfo minfo,
  int thisPriority,
  int thisDistance,
  float3 windDirection,
  struct vert thisrvA,
  struct vert thisrvB,
  struct vert thisrvC,
  read_only image3d_t tileHeightMap
) {
  uint offset = minfo.offset;
  uint size = minfo.size;

  if (localId < size) {
    int outOffset = minfo.idx;
    int uvOffset = minfo.uvOffset;
    int flags = minfo.flags;
    int orientation = flags & 0x7ff;

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

    normalout[outOffset + myOffset * 3    ] = rotate_vertex(normA, orientation);
    normalout[outOffset + myOffset * 3 + 1] = rotate_vertex(normB, orientation);
    normalout[outOffset + myOffset * 3 + 2] = rotate_vertex(normC, orientation);

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

    float4 uvA = (float4)(0);
    float4 uvB = (float4)(0);
    float4 uvC = (float4)(0);

    float4 pos = (float4)(minfo.x, minfo.y, minfo.z, 0);
    float4 vertA = (float4)(thisrvA.x, thisrvA.y, thisrvA.z, 0);
    float4 vertB = (float4)(thisrvB.x, thisrvB.y, thisrvB.z, 0);
    float4 vertC = (float4)(thisrvC.x, thisrvC.y, thisrvC.z, 0) ;

    float4 displacementA = (float4)(0);
    float4 displacementB = (float4)(0);
    float4 displacementC = (float4)(0);

    if (uvOffset >= 0) {
        uvA = uv[uvOffset + localId * 3];
        uvB = uv[uvOffset + localId * 3 + 1];
        uvC = uv[uvOffset + localId * 3 + 2];

        #if WIND_ENABLED
        int WindSwayingValue = (((int)uvA.w) >> MATERIAL_FLAG_WIND_SWAYING & 3);
        if (WindSwayingValue > 0) {
            const float maxHeight = 100.0;
            const float noiseResolution = 0.04;
            const float gridSnapping = 400.0;

            float heightFrac = (float)((flags >> 27) & 0x1F) / 31.0f;
            float height = 100.0f * heightFrac;

            bool isTree = WindSwayingValue == 2;
            float windT = elapsedTime * windSpeed;
            float heightBasedWindStrength = windStrength * heightFrac;
            float strengthA = clamp(fabs(vertA.y) / height, 0.0f, 1.0f);
            float strengthB = clamp(fabs(vertA.y) / height, 0.0f, 1.0f);
            float strengthC = clamp(fabs(vertA.y) / height, 0.0f, 1.0f);

            if(isTree) {
                float windNoiseA = mix(-0.5, 0.5, noise((thisrvA.pos.xz + (float2)(windT)) * noiseResolution));
                float windNoiseB = mix(-0.5, 0.5, noise((thisrvB.pos.xz + (float2)(windT)) * noiseResolution));
                float windNoiseC = mix(-0.5, 0.5, noise((thisrvC.pos.xz + (float2)(windT)) * noiseResolution));

                // Avoid over stretching which can cause issues in ComputeUVs
                strengthA *= 0.2;
                strengthB *= 0.2;
                strengthC *= 0.2;

                displacementA = ((windNoiseA * heightBasedWindStrength * strengthA) * normalize(windDirection)) + windDirection;
                displacementB = ((windNoiseB * heightBasedWindStrength * strengthB) * normalize(windDirection)) + windDirection;
                displacementC = ((windNoiseC * heightBasedWindStrength * strengthC) * normalize(windDirection)) + windDirection;
            } else {
                float3 offset = pos.xyz + (vertA.xyz + vertB.xyz + vertC.xyz / 3.0f);
                offset.x = round(offset.x / 400.0f) * 400.0f;
                offset.z = round(offset.z / 400.0f) * 400.0f;

                float gridNoise = mix(-0.5, 0.5, noise((offset.xz + (float2)(uni->windT)) * noiseResolution));
                float3 gridDisplacement = (uni->windStrength * gridNoise * heightFrac) * windDirection;

                displacementA.xyz = gridDisplacement * strengthA;
                displacementB.xyz = gridDisplacement * strengthB;
                displacementC.xyz = gridDisplacement * strengthC;
            }
        }
        #endif
    }

    vertA += pos + displacementA;
    vertB += pos + displacementB;
    vertC += pos + displacementC;

    // apply hillskew
    int plane = (flags >> 24) & 3;
    int hillskew = (flags >> 26) & 1;
    if (hillskew == 1) {
        hillskew_vertex(tileHeightMap, &vertA, hillskew, minfo.y, plane);
        hillskew_vertex(tileHeightMap, &vertB, hillskew, minfo.y, plane);
        hillskew_vertex(tileHeightMap, &vertC, hillskew, minfo.y, plane);
    }

    // position vertices in scene and write to out buffer
    vout[outOffset + myOffset * 3] = (struct vert){vertA.x, vertA.y, vertA.z, thisrvA.ahsl};
    vout[outOffset + myOffset * 3 + 1] = (struct vert){vertB.x, vertB.y, vertB.z, thisrvB.ahsl};
    vout[outOffset + myOffset * 3 + 2] = (struct vert){vertC.x, vertC.y, vertC.z, thisrvC.ahsl};


    if (uvOffset >= 0) {
      if ((((int)uvA.w) >> MATERIAL_FLAG_VANILLA_UVS & 1) == 1) {
        // Rotate the texture triangles to match model orientation
        uvA = rotate_vertex(uvA, orientation);
        uvB = rotate_vertex(uvB, orientation);
        uvC = rotate_vertex(uvC, orientation);

        // Shift texture triangles to world space
        float3 modelPos = convert_float3(pos.xyz);
        uvA.xyz += modelPos + displacementA.xyz;
        uvB.xyz += modelPos + displacementB.xyz;
        uvC.xyz += modelPos + displacementC.xyz;

        // For vanilla UVs, the first 3 components are an integer position vector
        if (hillskew == 1) {
            hillskew_vertex(tileHeightMap, &uvA, hillskew, minfo.y, plane);
            hillskew_vertex(tileHeightMap, &uvB, hillskew, minfo.y, plane);
            hillskew_vertex(tileHeightMap, &uvC, hillskew, minfo.y, plane);
        }
      }
    }

    uvout[outOffset + myOffset * 3]     = uvA;
    uvout[outOffset + myOffset * 3 + 1] = uvB;
    uvout[outOffset + myOffset * 3 + 2] = uvC;
  }
}
