#version 330

#include <utils/font.glsl>

#define PRIMITIVE_CUBE   0
#define PRIMITIVE_SPHERE 1
#define PRIMITIVE_LINE   2
#define PRIMITIVE_TEXT   3

#include PRIMITIVE_TYPE

flat in vec4 fColor;

#if PRIMITIVE_TYPE == PRIMITIVE_TEXT
flat in int  fChar;
     in vec2 fUV;
#endif

out vec4 FragColor;

void main() {
#if PRIMITIVE_TYPE == PRIMITIVE_TEXT
    if (!fontSample(fChar, fUV))
        discard;
#endif
    FragColor = fColor;
}