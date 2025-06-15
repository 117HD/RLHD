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
#pragma once

#include utils/color_utils.glsl

uniform samplerCubeArray skybox;
uniform vec4 skyboxProperties; // X = skyboxCurrentIdx, y = skyboxBlend, z = skyboxTargetIdx, w = cameraNearPlane

int getCurrentSkyboxIdx() { return int(skyboxProperties.x); }
float getSkyboxBlend() { return skyboxProperties.y; }
int getTargetSkyboxIdx() { return int(skyboxProperties.z); }
float getSkyboxOffset() { return skyboxProperties.w; }

bool canSampleSky() {
    return getCurrentSkyboxIdx() >= 0 || getTargetSkyboxIdx() >= 0;
}

vec3 sampleSky(vec3 viewDir, vec3 baseColor) {
    vec3 skyboxDir = viewDir;
    skyboxDir.y = -viewDir.y;

    vec3 currentSkyColor = baseColor;
    if(getCurrentSkyboxIdx() >= 0) {
        currentSkyColor = linearToSrgb(texture(skybox, vec4(skyboxDir, getCurrentSkyboxIdx())).rgb);
    }

    vec3 targetSkyColor = baseColor;
    if(getTargetSkyboxIdx() >= 0) {
        targetSkyColor = linearToSrgb(texture(skybox, vec4(skyboxDir, getTargetSkyboxIdx())).rgb);
    }

    return mix(currentSkyColor, targetSkyColor, getSkyboxBlend());
}