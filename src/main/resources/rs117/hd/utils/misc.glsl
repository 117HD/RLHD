/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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

#include utils/polyfills.glsl

uniform float elapsedTime;

// translates a value from a custom range into 0-1
float translateRange(float rangeStart, float rangeEnd, float value)
{
    return (value - rangeStart) / (rangeEnd - rangeStart);
}

// returns a value between 0-1 representing a frame of animation
// based on the length of the animation
float animationFrame(float animationDuration)
{
    if (animationDuration == 0)
        return 0.0;
    return mod(elapsedTime, animationDuration) / animationDuration;
}

vec2 animationFrame(vec2 animationDuration)
{
    if (animationDuration == vec2(0))
        return vec2(0);
    return mod(vec2(elapsedTime), vec2(animationDuration)) / animationDuration;
}

// used for blending overlays into underlays.
// adapted from http://www.alecjacobson.com/weblog/?p=1486
float pointToLine(vec2 lineA, vec2 lineB, vec2 point)
{
    // vector from A to B
    vec2 AB = lineB - lineA;

    // squared distance from A to B
    float ABsquared = dot(AB, AB);

    if (ABsquared == 0)
    {
        // A and B are the same point
        return 1.0;
    }
    else
    {
        // vector from A to p
        vec2 Ap = (point - lineA);
        float t = dot(Ap, AB) / ABsquared;
        return t;
    }
}

vec2 getUvs(vec3 uvw, int materialData, vec3 position) {
    if ((materialData >> MATERIAL_FLAG_WORLD_UVS & 1) == 1) {
        // Treat the input uvw as a normal vector for a plane that goes through origo,
        // and find the distance from the point to the plane
        float scale = length(uvw);

        vec3 N = uvw / scale;
        vec3 C1 = cross(vec3(0, 0, 1), N);
        vec3 C2 = cross(vec3(0, 1, 0), N);
        vec3 T = normalize(length(C1) > length(C2) ? C1 : C2);
        vec3 B = cross(N, T);
        mat3 TBN = mat3(T, B, N);

        return fract((TBN * position).xy / 128.f / scale);
    }

    return uvw.xy;
}
