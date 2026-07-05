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
    float falloff = exp(-d * d * 2.0); // smooth bell, ~0 by the sprite edge (wider = softer)
    // Fade the very edge to exactly zero so the discard boundary isn't visible.
    falloff *= 1.0 - smoothstep(0.8, 1.0, d);

    // Stars are drawn additively ON TOP of the already-gamma-corrected sky, so we
    // do NOT gamma-correct here (that would crush these small values to nothing).
    // A brightness boost compensates for energy spread across the sprite vs. the
    // old crisp ~1px analytic star.
    vec3 starColor = colorBlindnessCompensation(vColor) * vBrightness * falloff * 4.0;

    // Additive blending (GL_ONE, GL_ONE); alpha carries falloff for AA at edges.
    FragColor = vec4(starColor, falloff);
}
