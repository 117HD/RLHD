#pragma once

// Needs to match UIBuffer.java
layout(std140) uniform UIUniforms {
    ivec2 sourceDimensions;
    ivec2 targetDimensions;

    float colorBlindnessIntensity;
    vec4 alphaOverlay;

    bool showGammaCalibration;
    float gammaCalibrationTimer;
    float gammaCorrection;
};
