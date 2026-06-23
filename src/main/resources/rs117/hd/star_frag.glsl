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

#include <uniforms/global.glsl>
#include <utils/color_blindness.glsl>

in vec3 vColor;
in float vBrightness;

out vec4 FragColor;

void main() {
    if (vBrightness <= 0.0)
        discard;

    // Distance from the point center, 0 at center, 1 at the sprite edge.
    float d = length(gl_PointCoord - vec2(0.5)) * 2.0;
    if (d >= 1.0)
        discard;

    // Smooth Gaussian-like profile instead of a hard-edged disc. On lower-res
    // displays a tightly-clamped point sprite "twinkles" as it rotates: sub-pixel
    // motion makes a near-1px star snap between covering one pixel and straddling
    // several, pumping its total brightness frame to frame. A soft, wide profile
    // that fades smoothly to zero at the rim spreads the star across enough pixels
    // that sub-pixel motion redistributes energy rather than toggling it, which
    // removes the flicker.
    float falloff = exp(-d * d * 4.0); // smooth bell, ~0 by the sprite edge
    // Fade the very edge to exactly zero so the discard boundary isn't visible.
    falloff *= 1.0 - smoothstep(0.8, 1.0, d);

    // Stars are drawn additively ON TOP of the already-gamma-corrected sky, so we
    // do NOT gamma-correct here (that would crush these small values to nothing).
    // A brightness boost compensates for energy spread across the sprite vs. the
    // old crisp ~1px analytic star.
    vec3 starColor = colorBlindnessCompensation(vColor) * vBrightness * falloff * 6.0;

    // Additive blending (GL_ONE, GL_ONE); alpha carries falloff for AA at edges.
    FragColor = vec4(starColor, falloff);
}
