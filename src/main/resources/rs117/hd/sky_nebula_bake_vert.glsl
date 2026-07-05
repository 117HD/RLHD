#version 330

// Minimal fullscreen-triangle vertex shader for the one-time nebula cubemap bake.
// Self-contained (no UBO dependency) since it runs during init.

out vec2 fFacePos;

void main() {
    vec2 pos = vec2((gl_VertexID & 1) * 4.0 - 1.0, (gl_VertexID & 2) * 2.0 - 1.0);
    gl_Position = vec4(pos, 0.0, 1.0);
    fFacePos = pos; // [-1, 1] across the cube face
}
