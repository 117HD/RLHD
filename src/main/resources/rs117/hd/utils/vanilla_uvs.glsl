/*
 * Copyright (c) 2023, Adam <Adam@sigterm.info>
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

void compute_uv(vec3 cameraPos,
          vec3 posA,       vec3 posB,       vec3 posC,
    inout vec3  uvA, inout vec3  uvB, inout vec3  uvC
) {
    vec3 v1 = uvA;
    vec3 v2 = uvB - v1;
    vec3 v3 = uvC - v1;

    // Project vertex positions onto a plane going through the texture triangle
    vec3 vertexToCamera;
    vec3 uvNormal = cross(v2, v3);
    vertexToCamera = cameraPos - posA;
    posA += vertexToCamera * dot(uvA - posA, uvNormal) / dot(vertexToCamera, uvNormal);
    vertexToCamera = cameraPos - posB;
    posB += vertexToCamera * dot(uvB - posB, uvNormal) / dot(vertexToCamera, uvNormal);
    vertexToCamera = cameraPos - posC;
    posC += vertexToCamera * dot(uvC - posC, uvNormal) / dot(vertexToCamera, uvNormal);

    vec3 v4 = posA - v1;
    vec3 v5 = posB - v1;
    vec3 v6 = posC - v1;

    float d;
    vec3 perpv3 = cross(v3, uvNormal);
    d = 1.0f / dot(perpv3, v2);
    // (v4's distance along perpv3) * |perpv3| / (|perpv3| * (v2's distance along perpv3))
    uvA.x = dot(perpv3, v4) * d; // (v4's distance along perpv3) / (v2's distance along perpv3) => uvA.x
    uvB.x = dot(perpv3, v5) * d; // (v5's distance along perpv3) / (v2's distance along perpv3) => uvB.x
    uvC.x = dot(perpv3, v6) * d; // (v6's distance along perpv3) / (v2's distance along perpv3) => uvC.x

    vec3 perpv2 = cross(v2, uvNormal);
    d = 1.0f / dot(perpv2, v3);
    // (v4's distance along perpv2) * |perpv2| / (|perpv2| * (v3's distance along perpv2))
    uvA.y = dot(perpv2, v4) * d; // (v4's distance along perpv2) / (v3's distance along perpv2) => uvA.y
    uvB.y = dot(perpv2, v5) * d; // (v5's distance along perpv2) / (v3's distance along perpv2) => uvB.y
    uvC.y = dot(perpv2, v6) * d; // (v6's distance along perpv2) / (v3's distance along perpv2) => uvC.y
}
