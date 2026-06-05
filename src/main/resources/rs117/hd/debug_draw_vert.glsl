#version 330

#include <uniforms/global.glsl>
#include <utils/color_utils.glsl>

#define PRIMITIVE_CUBE   0
#define PRIMITIVE_SPHERE 1
#define PRIMITIVE_LINE   2
#define PRIMITIVE_TEXT   3

#include PRIMITIVE_TYPE

layout(location = 0) in vec3 aPosition;

#if PRIMITIVE_TYPE == PRIMITIVE_CUBE
layout(location = 1) in vec3 aCenter;
layout(location = 2) in vec3 aHalfExtents;
layout(location = 3) in int  aArgb;

#elif PRIMITIVE_TYPE == PRIMITIVE_SPHERE
layout(location = 1) in vec3  aCenter;
layout(location = 2) in float aRadius;
layout(location = 3) in int   aArgb;

#elif PRIMITIVE_TYPE == PRIMITIVE_LINE
layout(location = 1) in vec3  aStart;
layout(location = 2) in vec3  aEnd;
layout(location = 3) in float aThickness;
layout(location = 4) in int   aArgb;

#elif PRIMITIVE_TYPE == PRIMITIVE_TEXT
layout(location = 1) in vec3 aCenter;
layout(location = 2) in float aCharScale;
layout(location = 3) in int aCharCode;
layout(location = 4) in int aCharIndex;
layout(location = 5) in int aArgb;
#endif

flat out vec4 fColor;

#if PRIMITIVE_TYPE == PRIMITIVE_TEXT
flat out int  fChar;
     out vec2 fUV;
#endif

vec4 unpackArgb(int argb) {
    return vec4(
        ((argb >> 16) & 0xFF) / 255.0,
        ((argb >>  8) & 0xFF) / 255.0,
        ((argb >>  0) & 0xFF) / 255.0,
        ((argb >> 24) & 0xFF) / 255.0
    );
}

#if PRIMITIVE_TYPE == PRIMITIVE_LINE
mat4 lineModelMatrix(vec3 start, vec3 end, float thickness) {
    vec3  dir  = end - start;
    float len  = length(dir);
    vec3  mid  = (start + end) * 0.5;

    if (len < 1e-5)
        return mat4(1.0);

    vec3 yAxis = dir / len;
    vec3 ref   = abs(yAxis.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 xAxis = normalize(cross(ref, yAxis));
    vec3 zAxis = cross(yAxis, xAxis);

    return mat4(
        vec4(xAxis * thickness * 0.5, 0.0),
        vec4(yAxis * len       * 0.5, 0.0),
        vec4(zAxis * thickness * 0.5, 0.0),
        vec4(mid,                     1.0)
    );
}
#endif

void main() {
#if PRIMITIVE_TYPE == PRIMITIVE_CUBE
    mat4 model = mat4(
        vec4(aHalfExtents.x, 0.0,            0.0,            0.0),
        vec4(0.0,            aHalfExtents.y,  0.0,            0.0),
        vec4(0.0,            0.0,             aHalfExtents.z, 0.0),
        vec4(aCenter,                                         1.0)
    );
    fColor      = unpackArgb(aArgb);
    gl_Position = projectionMatrix * model * vec4(aPosition, 1.0);

#elif PRIMITIVE_TYPE == PRIMITIVE_SPHERE
    mat4 model = mat4(
        vec4(aRadius, 0.0,     0.0,     0.0),
        vec4(0.0,     aRadius, 0.0,     0.0),
        vec4(0.0,     0.0,     aRadius, 0.0),
        vec4(aCenter,                   1.0)
    );
    fColor      = unpackArgb(aArgb);
    gl_Position = projectionMatrix * model * vec4(aPosition, 1.0);

#elif PRIMITIVE_TYPE == PRIMITIVE_LINE
    fColor      = unpackArgb(aArgb);
    gl_Position = projectionMatrix * lineModelMatrix(aStart, aEnd, aThickness) * vec4(aPosition, 1.0);

#elif PRIMITIVE_TYPE == PRIMITIVE_TEXT
    vec2 quad = aPosition.xy - vec2(0.5);

    // Extract camera basis from the view-projection matrix.
    // If you have a separate view matrix available, use that instead.
    vec3 cameraRight = vec3(
        projectionMatrix[0][0],
        projectionMatrix[1][0],
        projectionMatrix[2][0]
    );

    vec3 cameraUp = vec3(
        projectionMatrix[0][1],
        projectionMatrix[1][1],
        projectionMatrix[2][1]
    );

    float glyphSize = aCharScale;

    vec3 worldPos =
        aCenter +
        cameraRight * ((float(aCharIndex) + quad.x) * glyphSize) +
        cameraUp    * (quad.y * glyphSize);

    fUV    = aPosition.xy;
    fColor = unpackArgb(aArgb);
    fChar  = aCharCode;

    gl_Position = projectionMatrix * vec4(worldPos, 1.0);
#endif
}