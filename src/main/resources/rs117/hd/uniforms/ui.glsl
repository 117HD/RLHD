#pragma once

layout(std140) uniform UBOUI {
    ivec2 sourceDimensions;
    ivec2 targetDimensions;
    vec4 alphaOverlay;
};

#include UI_SCALING_MODE
#define UI_SCALING_MODE_NEAREST 0
#define UI_SCALING_MODE_LINEAR 1
#define UI_SCALING_MODE_MITCHELL 2
#define UI_SCALING_MODE_CATROM 3
#define UI_SCALING_MODE_XBR 4
#define UI_SCALING_MODE_PIXEL 5
