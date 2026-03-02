#version 330

#include <utils/color_utils.glsl>

out vec4 fragColor;

uniform int queryId;

void main() {
    float hue = float(queryId % 1024) / 1024.0;
    fragColor = vec4(hsv2rgb(hue), 0.25);
}