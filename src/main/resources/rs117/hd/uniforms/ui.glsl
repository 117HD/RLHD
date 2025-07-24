#pragma once

layout(std140) uniform UBOUI {
    ivec2 sourceDimensions;
    ivec2 targetDimensions;
    vec4 alphaOverlay;
};
