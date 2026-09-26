#pragma once

#include <uniforms/global.glsl>

#include <utils/color_utils.glsl>
#include <utils/color_blindness.glsl>
#include <utils/color_filters.glsl>
#include <utils/misc.glsl>

vec3 applyColorAdjustments(vec3 srgb) {
    srgb = clamp(srgb, 0.0, 1.0);
    if (saturation != 1.0 || contrast != 1.0) {
        vec3 hsv = srgbToHsv(srgb);
        hsv.y *= saturation;
        hsv.z = 0.5 + (hsv.z - 0.5) * contrast;
        srgb = hsvToSrgb(hsv);
    }
    srgb = colorBlindnessCompensation(srgb);
    #if APPLY_COLOR_FILTER
        srgb = applyColorFilter(srgb);
    #endif
    return srgb;
}

vec3 applyOutputCorrection(vec3 srgb) {
    srgb = pow(srgb, vec3(gammaCorrection));
    #if WINDOWS_HDR_CORRECTION
        srgb = windowsHdrCorrection(srgb);
    #endif
    return srgb;
}
