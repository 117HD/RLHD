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
#version 330

// Star point-sprite pass. Each vertex is one star from a pre-generated list, so
// the cost scales with star count instead of screen pixels. The celestial
// rotation, projection, day/night fade and point size are all computed here
// (once per star) rather than per pixel.

#include <uniforms/global.glsl>

layout(location = 0) in vec3 aStarDir;     // field-space unit direction
layout(location = 1) in float aStarSize;   // relative size
layout(location = 2) in float aStarBright; // base brightness
layout(location = 3) in vec3 aStarColor;   // tint
layout(location = 4) in float aStarSpeed;  // rotation speed multiplier (parallax)

uniform vec2 viewportSize;

out vec3 vColor;
out float vBrightness;

const float SKY_HORIZON_OFFSET = 0.087;

void main() {
    // The procedural field was sampled at starDir = R * viewDir, so a star fixed
    // at field-direction aStarDir appears along viewDir = R^-1 * aStarDir. Apply
    // the inverse of the sky shader's two rotations (negate the angles).
    // aStarSpeed scales the rotation per layer so dim/distant stars drift slower
    // than bright/near ones, giving the sky a subtle parallax depth.
    float rotY = -elapsedTime * (2.0 * 3.14159265 / 3600.0) * aStarSpeed;
    float rotX = -elapsedTime * (2.0 * 3.14159265 / 10800.0) * aStarSpeed;
    float cosY = cos(rotY), sinY = sin(rotY);
    float cosX = cos(rotX), sinX = sin(rotX);

    // Inverse order: undo X rotation first, then Y (sky applied Y then X).
    vec3 dir = aStarDir;
    dir = vec3(dir.x, cosX * dir.y - sinX * dir.z, sinX * dir.y + cosX * dir.z);
    dir = vec3(cosY * dir.x + sinY * dir.z, dir.y, -sinY * dir.x + cosY * dir.z);

    // Occlude stars behind the moon disk. The moon is drawn opaque in the sky base
    // pass; without this, the additively-blended stars would show through it. Fade
    // smoothly across the rim (rather than a hard cut) so stars don't pop in/out as
    // the sky rotates them past the moon's edge.
    float moonOcclusion = 1.0;
    if (skyMoonIllumination > 0.001 && moonVisibility > 0.0) {
        vec3 moonDir = normalize(vec3(skyMoonDir.x, -skyMoonDir.y + SKY_HORIZON_OFFSET, skyMoonDir.z));
        float moonDot = dot(dir, moonDir);
        // 0 inside the disk (occluded), 1 outside a slightly larger soft rim.
        // cos values: 0.99951 = disk edge, smaller cos = wider angle from center.
        moonOcclusion = smoothstep(0.99951, 0.9991, moonDot);
    }

    // Project a far-but-finite point along the star direction. projectionMatrix is
    // world->clip (same as scene geometry), so we offset from the camera position.
    // Depth test is disabled for the sky/star pass, so the exact distance only
    // needs to be large enough to read as "far".
    vec4 clip = projectionMatrix * vec4(cameraPos + dir * 1.0e6, 1.0);
    if (clip.w <= 0.0) {
        // Behind the camera — push off-screen so it's clipped.
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        gl_PointSize = 0.0;
        return;
    }
    gl_Position = clip;

    // === Day/night visibility (mirrors sky_frag's nightSkyBlend) ===
    // viewDir convention in the sky shader: upAmount = -viewDir.y. Here dir is the
    // world view direction toward the star.
    float upAmount = -dir.y;

    vec3 sunDir = normalize(vec3(skySunDir.x, -skySunDir.y + SKY_HORIZON_OFFSET, skySunDir.z));
    vec2 viewHoriz = vec2(dir.x, dir.z);
    float viewHorizLen = length(viewHoriz);
    vec3 viewHorizontal = viewHorizLen > 1e-4 ? vec3(viewHoriz.x, 0.0, viewHoriz.y) / viewHorizLen : vec3(0.0);
    vec3 sunHorizontal = normalize(vec3(sunDir.x, 0.0, sunDir.z));
    float sunFacing = dot(viewHorizontal, sunHorizontal) * smoothstep(0.0, 0.35, viewHorizLen);
    float sunSideBlend = smoothstep(0.0, 1.0, (sunFacing + 1.0) * 0.5);

    float zenithBlend = smoothstep(-0.1, 0.7, upAmount);
    float nightFade = smoothstep(-0.26, 0.0, skySunDir.y);

    float baseProgress = 1.0 - nightFade;
    float sunProximity = sunSideBlend * (1.0 - zenithBlend);
    float nightSkyBlend = pow(baseProgress, mix(0.4, 0.9, sunProximity)) * starVisibility;

    // Fade out at the horizon. Slightly higher band than the sky's nebula fade so
    // individual stars don't linger visibly below the horizon line.
    float horizonStarFade = smoothstep(0.0, 0.12, upAmount);

    float visibility = nightSkyBlend * horizonStarFade * moonOcclusion;

    vColor = aStarColor;
    // Compress the top end so the brightest stars don't read as harsh hotspots,
    // while leaving the dim/mid stars essentially untouched.
    vBrightness = min(aStarBright, 0.4) * visibility;

    // Point size in pixels. Scales with vertical resolution so stars keep a
    // consistent apparent size, plus a gentle brightness term so brighter stars
    // read a touch larger.
    //
    // The MINIMUM is the key anti-twinkle knob: a sprite only a pixel or two wide
    // can't hold a stable soft profile, so on lower-res displays it flickers as it
    // drifts sub-pixel. Clamping to a few pixels minimum guarantees every star
    // spreads across enough pixels for its smooth falloff to redistribute energy
    // under motion instead of toggling it. The maximum keeps the brightest stars
    // from reading as blobs.
    float sizePixels = aStarSize * viewportSize.y * 0.003 * (0.9 + 0.15 * vBrightness);
    gl_PointSize = visibility > 0.001 ? clamp(sizePixels, 1.75, 3.5) : 0.0;
}
