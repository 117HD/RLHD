#version 330

// One triangle over the whole target

void main() {
    gl_Position = vec4((gl_VertexID & 1) * 4.0 - 1.0, (gl_VertexID & 2) * 2.0 - 1.0, 0, 1);
}
