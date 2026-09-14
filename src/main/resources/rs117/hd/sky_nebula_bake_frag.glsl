#version 330

// One-time bake of the procedural nebula into a cubemap face. The nebula is a
// pure function of view direction (no animation), so evaluating its multi-octave
// fBm once per texel and sampling it thereafter removes the per-frame per-pixel
// noise cost from the sky shader entirely.
//
// starfield.glsl references TAU (constants) and nebulaVisibility (global UBO) in
// its star/starfield helpers, so pull those in even though the bake only needs
// proceduralNebula().
#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/constants.glsl>

// NEBULA_BAKE tells starfield.glsl to expose the procedural nebula path rather
// than declaring/sampling the (not-yet-existing) nebula cubemap.
#define NEBULA_BAKE
#include <utils/starfield.glsl>

in vec3 fFaceDirection;

out vec4 FragColor;

void main() {
    FragColor = vec4(proceduralNebula(normalize(fFaceDirection)), 1.0);
}
