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

void compute_uv(
            float3  posA, float3  posB, float3  posC,
/* inout */ float4*  uvA, float4*  uvB, float4*  uvC
) {
    float3 v1 = (*uvA).xyz;
    float3 v2 = (*uvB).xyz - v1;
    float3 v3 = (*uvC).xyz - v1;

    float3 v4 = posA - v1;
    float3 v5 = posB - v1;
    float3 v6 = posC - v1;

    float3 v7 = cross(v2, v3);

    float3 v8 = cross(v3, v7);
    float d = 1.0f / dot(v8, v2);

    float u0 = dot(v8, v4) * d;
    float u1 = dot(v8, v5) * d;
    float u2 = dot(v8, v6) * d;

    v8 = cross(v2, v7);
    d = 1.0f / dot(v8, v3);

    float v0_ = dot(v8, v4) * d;
    float v1_ = dot(v8, v5) * d;
    float v2_ = dot(v8, v6) * d;

    (*uvA).xyz = (float3)(u0, v0_, 0);
    (*uvB).xyz = (float3)(u1, v1_, 0);
    (*uvC).xyz = (float3)(u2, v2_, 0);
}
