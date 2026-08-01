#version 330

uniform sampler2DArray staticCache;
uniform int maxFaceResolution;

flat in int vLayer;
flat in float vRectSize;
in vec2 vLocalPx;

void main() {
    vec2 srcCoord = (vLocalPx / vRectSize) * float(maxFaceResolution);
    gl_FragDepth = texelFetch(staticCache, ivec3(ivec2(srcCoord), vLayer), 0).r;
}