#pragma once

#include <uniforms/global.glsl>
#include <utils/color_utils.glsl>
#include <utils/color_blindness.glsl>
#include <utils/color_filters.glsl>
#include <utils/misc.glsl>

// Input is nonlinear sRGB. Scene fog is applied after these color adjustments.
vec3 applyColorAdjustments(vec3 color) {
    color = clamp(color, 0.0, 1.0);
    if (saturation != 1.0 || contrast != 1.0) {
        vec3 hsv = srgbToHsv(color);
        hsv.y *= saturation;
        hsv.z = 0.5 + (hsv.z - 0.5) * contrast;
        color = hsvToSrgb(hsv);
    }
    color = colorBlindnessCompensation(color);
    #if APPLY_COLOR_FILTER
        color = applyColorFilter(color);
    #endif
    return color;
}

// Apply after fog and all other color composition.
vec3 applyOutputCorrection(vec3 color) {
    color = pow(color, vec3(gammaCorrection));
    #if WINDOWS_HDR_CORRECTION
        color = windowsHdrCorrection(color);
    #endif
    return color;
}
