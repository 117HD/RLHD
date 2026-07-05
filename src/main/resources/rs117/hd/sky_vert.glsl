#version 330

#include <uniforms/global.glsl>

out vec2 fScreenPos;

void main() {
    // Fullscreen triangle - generates vertices at (-1,-1), (3,-1), (-1,3)
    vec2 pos = vec2((gl_VertexID & 1) * 4.0 - 1.0, (gl_VertexID & 2) * 2.0 - 1.0);
    gl_Position = vec4(pos, 0.0, 1.0); // Near the far plane

    // Pass screen position to fragment shader for per-pixel ray calculation
    fScreenPos = pos;
}
