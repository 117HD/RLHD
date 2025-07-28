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

#include THREAD_COUNT
#include FACES_PER_THREAD

#include "common.cl"
#include "priority_render.cl"

__kernel
__attribute__((work_group_size_hint(THREAD_COUNT, 1, 1)))
void sortModel(
  __local struct shared_data *shared,
  __global const struct ModelInfo *ol,
  __global const struct VertexData *vb,
  __global const struct UVData *uv,
  __global const float4 *normal,
  __global struct VertexData *vout,
  __global struct UVData *uvout,
  __global float4 *normalout,
  __constant struct UBOCompute *uni,
  read_only image3d_t tileHeightMap
) {
  size_t groupId = get_group_id(0);
  size_t localId = get_local_id(0) * FACES_PER_THREAD;
  struct ModelInfo minfo = ol[groupId];
  int4 pos = (int4)(minfo.x, minfo.y >> 16, minfo.z, 0);

  if (localId == 0) {
    shared->min10 = 6000;
    for (int i = 0; i < 12; ++i) {
      shared->totalNum[i] = 0;
      shared->totalDistance[i] = 0;
    }
    for (int i = 0; i < 18; ++i) {
      shared->totalMappedNum[i] = 0;
    }
  }

  struct ObjectWindSample windSample;
  #if WIND_DISPLACEMENT
  {
      float2 modelPos = (float2)(minfo.x, minfo.z);
      float modelNoise = noise((modelPos + (float2)(uni->windOffset, uni->windOffset)) * WIND_DISPLACEMENT_NOISE_RESOLUTION);
      float angle = modelNoise * (PI / 2.0f);
      float c = cos(angle);
      float s = sin(angle);
      float y = (float)(minfo.y >> 16);
      float height = (float)(minfo.y & 0xffff);

      windSample.direction = normalize((float3)(uni->windDirectionX * c + uni->windDirectionZ * s, 0.0f, -uni->windDirectionX * s + uni->windDirectionZ * c));
      windSample.heightBasedStrength = clamp((fabs(y) + height) / uni->windCeiling, 0.0f, 1.0f) * uni->windStrength;
      windSample.displacement = windSample.direction * windSample.heightBasedStrength * modelNoise;
  }
  #endif

  int prio[FACES_PER_THREAD];
  int dis[FACES_PER_THREAD];
  struct VertexData v1[FACES_PER_THREAD];
  struct VertexData v2[FACES_PER_THREAD];
  struct VertexData v3[FACES_PER_THREAD];

  for (int i = 0; i < FACES_PER_THREAD; i++) {
    get_face(shared, uni, vb, localId + i, minfo, &prio[i], &dis[i], &v1[i], &v2[i], &v3[i]);
  }

  barrier(CLK_LOCAL_MEM_FENCE);

  for (int i = 0; i < FACES_PER_THREAD; i++) {
    add_face_prio_distance(shared, uni, localId + i, minfo, v1[i], v2[i], v3[i], prio[i], dis[i], pos);
  }

  barrier(CLK_LOCAL_MEM_FENCE);

  int prioAdj[FACES_PER_THREAD];
  int idx[FACES_PER_THREAD];
  for (int i = 0; i < FACES_PER_THREAD; i++) {
    idx[i] = map_face_priority(shared, localId + i, minfo, prio[i], dis[i], &prioAdj[i]);
  }

  barrier(CLK_LOCAL_MEM_FENCE);

  for (int i = 0; i < FACES_PER_THREAD; i++) {
    insert_face(shared, localId + i, minfo, prioAdj[i], dis[i], idx[i]);
  }

  barrier(CLK_LOCAL_MEM_FENCE);

  for (int i = 0; i < FACES_PER_THREAD; i++) {
    sort_and_insert(shared, uv, normal, vout, uvout, normalout, uni, localId + i, minfo, prioAdj[i], dis[i], v1[i], v2[i], v3[i], tileHeightMap, windSample);
  }
}
