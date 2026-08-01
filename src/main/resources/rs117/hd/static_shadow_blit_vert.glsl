#version 330

layout(location = 0) in ivec4 instanceRect; // x, y, size, localSlot (relative to the bound array)

uniform int atlasSize;
uniform int face;

flat out int vLayer;
flat out float vRectSize;
out vec2 vLocalPx;

void main() {
    vec2 corner = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1);
    vec2 pixelPos = vec2(instanceRect.xy) + corner * float(instanceRect.z);
    vec2 ndc = (pixelPos / float(atlasSize)) * 2.0 - 1.0;

    gl_Position = vec4(ndc, 0.0, 1.0);
    vLocalPx = corner * float(instanceRect.z);
    vRectSize = float(instanceRect.z);
    vLayer = instanceRect.w * 6 + face;
}