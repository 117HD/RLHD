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
#include <utils/constants.glsl>

// NEBULA_BAKE tells starfield.glsl to expose the procedural nebula path rather
// than declaring/sampling the (not-yet-existing) nebula cubemap.
#define NEBULA_BAKE
#include <utils/starfield.glsl>

in vec2 fFacePos;

out vec4 FragColor;

// Per-face basis: a direction for fFacePos == (0,0), plus right/up axes spanning
// [-1, 1]. dir = normalize(forward + x*right + y*up).
uniform vec3 faceForward;
uniform vec3 faceRight;
uniform vec3 faceUp;

void main() {
    vec3 dir = normalize(faceForward + fFacePos.x * faceRight + fFacePos.y * faceUp);
    FragColor = vec4(proceduralNebula(dir), 1.0);
}
