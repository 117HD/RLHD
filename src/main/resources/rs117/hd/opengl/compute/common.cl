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

#define PI 3.1415926535897932384626433832795f
#define UNIT PI / 1024.0f

float3 to_screen(__constant struct uniform *uni, float3 vertex);
float4 rotate_vertex(float4 vector, int orientation);
float vertex_distance(__constant struct uniform *uni, float4 vertex);
int face_distance(__constant struct uniform *uni, float4 vA, float4 vB, float4 vC);
bool face_visible(__constant struct uniform *uni, float3 vA, float3 vB, float3 vC, int4 position);

float3 to_screen(__constant struct uniform *uni, float3 vertex) {
  float yawSin = sin(uni->cameraYaw);
  float yawCos = cos(uni->cameraYaw);

  float pitchSin = sin(uni->cameraPitch);
  float pitchCos = cos(uni->cameraPitch);

  float rotatedX = (vertex.z * yawSin) + (vertex.x * yawCos);
  float rotatedZ = (vertex.z * yawCos) - (vertex.x * yawSin);

  float var13 = (vertex.y * pitchCos) - (rotatedZ * pitchSin);
  float var12 = (vertex.y * pitchSin) + (rotatedZ * pitchCos);

  float x = rotatedX * uni->zoom / var12 + uni->centerX;
  float y = var13 * uni->zoom / var12 + uni->centerY;
  float z = -var12; // in OpenGL depth is negative

  return (float3) (x, y, z);
}

/*
 * Rotate a vertex by a given orientation in JAU
 */
float4 rotate_vertex(float4 vector, int orientation) {
  float rad = orientation * UNIT;
  float s = sin(rad);
  float c = cos(rad);
  float x = vector.z * s + vector.x * c;
  float z = vector.z * c - vector.x * s;
  return (float4)(x, vector.y, z, vector.w);
}

/*
 * Calculate the distance to a vertex given the camera angle
 */
float vertex_distance(__constant struct uniform *uni, float4 vertex) {
  float j = vertex.z * cos(uni->cameraYaw) - vertex.x * sin(uni->cameraYaw);
  float l = vertex.y * sin(uni->cameraPitch) + j * cos(uni->cameraPitch);
  return l;
}

/*
 * Calculate the distance to a face
 */
int face_distance(__constant struct uniform *uni, float4 vA, float4 vB, float4 vC) {
  float dvA = vertex_distance(uni, vA);
  float dvB = vertex_distance(uni, vB);
  float dvC = vertex_distance(uni, vC);
  float faceDistance = (dvA + dvB + dvC) / 3;
  return (int) faceDistance;
}

/*
 * Test if a face is visible (not backward facing)
 */
bool face_visible(__constant struct uniform *uni, float3 vA, float3 vB, float3 vC, int4 position) {
  // Move model to scene location, and account for camera offset
  float3 cameraPos = (float3)(uni->cameraX, uni->cameraY, uni->cameraZ);
  float3 modelPos = convert_float3(position.xyz);

  float3 lA = vA + modelPos - cameraPos;
  float3 lB = vB + modelPos - cameraPos;
  float3 lC = vC + modelPos - cameraPos;

  float3 sA = to_screen(uni, lA);
  float3 sB = to_screen(uni, lB);
  float3 sC = to_screen(uni, lC);

  return (sA.x - sB.x) * (sC.y - sB.y) - (sC.x - sB.x) * (sA.y - sB.y) > 0;
}

