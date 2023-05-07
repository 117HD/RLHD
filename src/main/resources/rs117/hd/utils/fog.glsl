/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

#include uniforms/camera.glsl

uniform int useFog;
uniform int fogDepth;
uniform int drawDistance;

#define TILE_SIZE 128
#define FOG_SCENE_EDGE_MIN TILE_SIZE
#define FOG_SCENE_EDGE_MAX (103 * TILE_SIZE)
#define FOG_CORNER_ROUNDING 1.5
#define FOG_CORNER_ROUNDING_SQUARED FOG_CORNER_ROUNDING * FOG_CORNER_ROUNDING

float fogFactorLinear(const float dist, const float start, const float end) {
    return 1.0 - clamp((dist - start) / (end - start), 0.0, 1.0);
}

float calculateFogAmount(vec3 position) {
    if (fogDepth == 0)
        return 0.f;

    int fogWest = max(FOG_SCENE_EDGE_MIN, cameraX - drawDistance);
    int fogEast = min(FOG_SCENE_EDGE_MAX, cameraX + drawDistance - TILE_SIZE);
    int fogSouth = max(FOG_SCENE_EDGE_MIN, cameraZ - drawDistance);
    int fogNorth = min(FOG_SCENE_EDGE_MAX, cameraZ + drawDistance - TILE_SIZE);

    // Calculate distance from the scene edge
    float xDist = min(position.x - fogWest, fogEast - position.x);
    float zDist = min(position.z - fogSouth, fogNorth - position.z);
    float nearestEdgeDistance = min(xDist, zDist);
    float secondNearestEdgeDistance = max(xDist, zDist);
    float fogDistance = nearestEdgeDistance
        - FOG_CORNER_ROUNDING * TILE_SIZE * max(0,
            (nearestEdgeDistance + FOG_CORNER_ROUNDING_SQUARED)
            / (secondNearestEdgeDistance + FOG_CORNER_ROUNDING_SQUARED)
        );

    float edgeFogDepth = 50;
    float edgeFogAmount = fogFactorLinear(fogDistance, 0, edgeFogDepth * (TILE_SIZE / 10)) * useFog;

    // Use a combination of two different methods of calculating distance fog.
    // The is super arbitrary and is only eyeballed to provide a similar overall
    // appearance between equal fog depths at different draw distances.

    float fogStart1 = drawDistance * 0.85;
    float distance1 = length(vec3(cameraX, cameraY, cameraZ) - vec3(position.x, cameraY, position.z));
    float distanceFogAmount1 = clamp((distance1 - fogStart1) / (drawDistance - fogStart1), 0, 1);

    float minFogStart = 0.0;
    float maxFogStart = 0.3;
    int fogDepth2 = int(fogDepth * drawDistance / (TILE_SIZE * 100.0));
    float fogDepthMultiplier = clamp(fogDepth2, 0, 1000) / 1000.0;
    float fogStart2 = (maxFogStart - (fogDepthMultiplier * (maxFogStart - minFogStart))) * float(drawDistance);
    float camToVertex = length(vec3(cameraX, cameraY, cameraZ) - vec3(
        position.x,
        mix(float(position.y), float(cameraY), 0.5),
        position.z
    ));
    float distance2 = max(camToVertex - fogStart2, 0) / max(drawDistance - fogStart2, 1);
    float density = fogDepth2 / 100.0;
    float distanceFogAmount2 = 1 - clamp(exp(-distance2 * density), 0, 1);

    // Combine distance fogs
    float distanceFogAmount = max(distanceFogAmount1, distanceFogAmount2);

    // Combine distance fog with edge fog
    return max(distanceFogAmount, edgeFogAmount);
}
