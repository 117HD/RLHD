/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
#include "common.cl"

__kernel
__attribute__((reqd_work_group_size(6, 1, 1)))
void passthroughModel(
  __global const struct ModelInfo *ol,
  __global const struct VertexData *vb,
  __global const struct UVData *uv,
  __global const float4 *normal,
  __global struct VertexData *vout,
  __global struct UVData *uvout,
  __global float4 *normalout
) {
  size_t groupId = get_group_id(0);
  size_t localId = get_local_id(0);
  struct ModelInfo minfo = ol[groupId];

  int offset = minfo.offset;
  int outOffset = minfo.idx;
  int uvOffset = minfo.uvOffset;

  if (localId >= (size_t) minfo.size) {
    return;
  }

  uint ssboOffset = localId;
  struct VertexData thisA, thisB, thisC;

  thisA = vb[offset + ssboOffset * 3];
  thisB = vb[offset + ssboOffset * 3 + 1];
  thisC = vb[offset + ssboOffset * 3 + 2];

  uint myOffset = localId;
  float3 pos = convert_float3((int3)(minfo.x, minfo.y >> 16, minfo.z));

  float3 vertA = (float3)(thisA.x, thisA.y, thisA.z) + pos;
  float3 vertB = (float3)(thisB.x, thisB.y, thisB.z) + pos;
  float3 vertC = (float3)(thisC.x, thisC.y, thisC.z) + pos;

  // position vertices in scene and write to out buffer
  vout[outOffset + myOffset * 3] = (struct VertexData){vertA.x, vertA.y, vertA.z, thisA.ahsl};
  vout[outOffset + myOffset * 3 + 1] = (struct VertexData){vertB.x, vertB.y, vertB.z, thisB.ahsl};
  vout[outOffset + myOffset * 3 + 2] = (struct VertexData){vertC.x, vertC.y, vertC.z, thisC.ahsl};

  if (uvOffset < 0) {
    uvout[outOffset + myOffset * 3]     = (struct UVData){0.0f, 0.0f, 0.0f, 0};
    uvout[outOffset + myOffset * 3 + 1] = (struct UVData){0.0f, 0.0f, 0.0f, 0};
    uvout[outOffset + myOffset * 3 + 2] = (struct UVData){0.0f, 0.0f, 0.0f, 0};
  } else {
    uvout[outOffset + myOffset * 3]     = uv[uvOffset + localId * 3];
    uvout[outOffset + myOffset * 3 + 1] = uv[uvOffset + localId * 3 + 1];
    uvout[outOffset + myOffset * 3 + 2] = uv[uvOffset + localId * 3 + 2];
  }
  
  float4 normA, normB, normC;
  
  normA = normal[offset + ssboOffset * 3    ];
  normB = normal[offset + ssboOffset * 3 + 1];
  normC = normal[offset + ssboOffset * 3 + 2];
  
  normalout[outOffset + myOffset * 3]     = normA;
  normalout[outOffset + myOffset * 3 + 1] = normB;
  normalout[outOffset + myOffset * 3 + 2] = normC;
}
