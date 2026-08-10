#version 330

out vec2 screenUV;

void main() {
    vec2 pos[3] = vec2[3](vec2(-1, -1), vec2(3, -1), vec2(-1, 3));
    gl_Position = vec4(pos[gl_VertexID], 1.0, 1.0);
    screenUV = pos[gl_VertexID] * 0.5 + 0.5;
}
