#version 330

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/output_transform.glsl>

in vec3 vColor;
in float vBrightness;

out vec4 FragColor;

void main() {
    if (vBrightness <= 0.0)
        discard;

    float d = length(gl_PointCoord - vec2(0.5)) * 2.0;
    if (d >= 1.0)
        discard;

    // A soft profile avoids sub-pixel brightness flicker.
    float falloff = exp(-d * d * 2.0); // smooth bell, ~0 by the sprite edge (wider = softer)
    falloff *= 1.0 - smoothstep(0.8, 1.0, d);

    // Vertex colors are authored in sRGB; brightness and falloff scale linear light.
    vec3 starColor = srgbToLinear(vColor) * vBrightness * falloff * 4.0;
    starColor = linearToSrgb(starColor);
    starColor = applyColorAdjustments(starColor);
    starColor = applyOutputCorrection(starColor);

    // Alpha carries edge antialiasing for additive blending.
    FragColor = vec4(starColor, falloff);
}
