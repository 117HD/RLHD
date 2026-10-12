#version 330

// Minimal fullscreen-triangle vertex shader for the one-time nebula cubemap bake.
// Self-contained (no UBO dependency) since it runs during init.

// OpenGL cubemap face index: +X, -X, +Y, -Y, +Z, -Z.
uniform int cubeFace;

// Unnormalized cubemap direction. Interpolating this is equivalent to interpolating the
// face position and applying the face basis in the fragment shader, but moves the basis
// work to the three vertices of the fullscreen triangle.
out vec3 fFaceDirection;

void main() {
    vec2 pos = vec2((gl_VertexID & 1) * 4.0 - 1.0, (gl_VertexID & 2) * 2.0 - 1.0);
    gl_Position = vec4(pos, 0.0, 1.0);

    int axis = cubeFace / 2;
    vec3 forward = vec3(0.0);
    forward[axis] = (cubeFace & 1) == 0 ? 1.0 : -1.0;

    // The Y faces have a Z up axis; all other faces use -Y. right is then the
    // standard OpenGL cubemap orientation for the selected forward/up pair.
    vec3 up = axis == 1 ? vec3(0.0, 0.0, cubeFace == 2 ? 1.0 : -1.0) : vec3(0.0, -1.0, 0.0);
    vec3 right = cross(forward, up);
    fFaceDirection = forward + pos.x * right + pos.y * up;
}
