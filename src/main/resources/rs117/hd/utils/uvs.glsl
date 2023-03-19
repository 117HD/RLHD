/*
 * Copyright (c) 2023, Adam <Adam@sigterm.info>
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
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

void computeUvs(const int materialData, const vec3 pos[3], inout vec3 uvw[3]) {
    if ((materialData >> MATERIAL_FLAG_WORLD_UVS & 1) == 1) {
        // Treat the input uvw as a normal vector for a plane that goes through origo,
        // and find the distance from the point to the plane
        float scale = 1. / length(uvw[0]);

        vec3 N = uvw[0] * scale;
        vec3 C1 = cross(vec3(0, 0, 1), N);
        vec3 C2 = cross(vec3(0, 1, 0), N);
        vec3 T = normalize(length(C1) > length(C2) ? C1 : C2);
        vec3 B = cross(N, T);
        mat3 TBN = mat3(T, B, N);

        for (int i = 0; i < 3; i++)
            uvw[i].xy = (TBN * pos[i]).xy / 128. * scale;
    } else if ((materialData >> MATERIAL_FLAG_IS_VANILLA_TEXTURED & 1) == 1) {
        vec3 v1 = uvw[0];
        vec3 v2 = uvw[1] - v1;
        vec3 v3 = uvw[2] - v1;

        vec3 uvNormal = cross(v2, v3);
        vec3 perpv2 = cross(v2, uvNormal);
        vec3 perpv3 = cross(v3, uvNormal);
        float du = 1.0f / dot(perpv3, v2);
        float dv = 1.0f / dot(perpv2, v3);

        for (int i = 0; i < 3; i++) {
            vec3 p = pos[i];
            #ifdef USE_VANILLA_UV_PROJECTION
            // Project vertex positions onto a plane going through the texture triangle
            vec3 vertexToCamera = vec3(cameraX, cameraY, cameraZ) - p;
            p += vertexToCamera * dot(uvw[i] - p, uvNormal) / dot(vertexToCamera, uvNormal);
            #endif

            // uvw[i].x = (v4's distance along perpv3) / (v2's distance along perpv3)
            // uvw[i].y = (v4's distance along perpv2) / (v3's distance along perpv2)
            vec3 v = p - v1;
            uvw[i].xy = vec2(
                dot(perpv3, v) * du,
                dot(perpv2, v) * dv
            );
        }
    }
}
