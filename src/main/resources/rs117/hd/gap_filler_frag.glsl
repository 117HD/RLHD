#version 330

out vec4 FragColor;

void main() {
    // Write the smallest depth which won't round to zero for DEPTH_COMPONENT_32F
    gl_FragDepth = 1.17549435e-38;
    FragColor = vec4(0, 0, 0, 1);
}
