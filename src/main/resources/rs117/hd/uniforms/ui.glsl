#pragma once

layout(std140) uniform UBOUI {
    ivec2 sourceDimensions;
    ivec2 targetDimensions;

    float colorBlindnessIntensity;
    vec4 alphaOverlay;
    ivec4 shadowMapOverlayDimensions;

    bool showGammaCalibration;
    float gammaCalibrationTimer;
    float gammaCorrection;
};
