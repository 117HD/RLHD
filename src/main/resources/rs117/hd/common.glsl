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

#include to_screen.glsl

/*
 * Rotate a vertex by a given orientation in JAU
 */
vec3 rotate(vec3 vertex, int orientation) {
    float rad = orientation * UNIT;
    float s = sin(rad);
    float c = cos(rad);
    float x = vertex.z * s + vertex.x * c;
    float z = vertex.z * c - vertex.x * s;
    return vec3(x, vertex.y, z);
}
vec4 rotate(vec4 vertex, int orientation) {
    return vec4(rotate(vertex.xyz, orientation), vertex.w);
}

/*
 * Calculate the distance to a vertex given the camera angle
 */
float distance(vec3 vertex) {
  float j = vertex.z * cos(cameraYaw) - vertex.x * sin(cameraYaw);
  float l = vertex.y * sin(cameraPitch) + j * cos(cameraPitch);
  return l;
}

/*
 * Calculate the distance to a face
 */
int face_distance(vec3 vA, vec3 vB, vec3 vC) {
  float dvA = distance(vA);
  float dvB = distance(vB);
  float dvC = distance(vC);
  float faceDistance = (dvA + dvB + dvC) / 3;
  return int(faceDistance);
}

/*
 * Convert a vertex to screen space
 */
vec3 toScreen(vec3 vertex) {
  float yawSin = sin(cameraYaw);
  float yawCos = cos(cameraYaw);

  float pitchSin = sin(cameraPitch);
  float pitchCos = cos(cameraPitch);

  float rotatedX = vertex.z * yawSin + vertex.x * yawCos;
  float rotatedZ = vertex.z * yawCos - vertex.x * yawSin;

  float var13 = vertex.y * pitchCos - rotatedZ * pitchSin;
  float var12 = vertex.y * pitchSin + rotatedZ * pitchCos;

  float x = rotatedX * zoom / var12 + centerX;
  float y = var13 * zoom / var12 + centerY;
  float z = -var12; // in OpenGL depth is negative

  return vec3(x, y, z);
}

/*
 * Test if a face is visible (not backward facing)
 */
bool face_visible(vec3 vA, vec3 vB, vec3 vC, vec3 position) {
  // Move model to scene location, and account for camera offset
  vec3 cameraPos = vec3(cameraX, cameraY, cameraZ);
  vA += position - cameraPos;
  vB += position - cameraPos;
  vC += position - cameraPos;

  vec3 sA = toScreen(vA);
  vec3 sB = toScreen(vB);
  vec3 sC = toScreen(vC);

  return (sA.x - sB.x) * (sC.y - sB.y) - (sC.x - sB.x) * (sA.y - sB.y) > 0;
}
