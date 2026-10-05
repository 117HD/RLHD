#version 330

#include REVERSE_Z

uniform sampler2DMS sceneDepth;
uniform int sampleCount;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = REVERSE_Z == 1.0 ? 0.0 : 1.0;

    for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
        float sampleDepth = texelFetch(sceneDepth, pixel, sampleIndex).r;
        depth = REVERSE_Z == 1.0 ? max(depth, sampleDepth) : min(depth, sampleDepth);
    }

    gl_FragDepth = depth;
}
