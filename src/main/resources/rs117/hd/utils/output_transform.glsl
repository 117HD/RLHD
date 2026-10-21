#pragma once

#include <uniforms/global.glsl>

#include <utils/color_utils.glsl>
#include <utils/color_blindness.glsl>
#include <utils/color_filters.glsl>
#include <utils/hash.glsl>
#include <utils/misc.glsl>

vec3 applyColorAdjustments(vec3 srgb) {
    srgb = clamp(srgb, 0.0, 1.0);
    if (uboGlobal.saturation != 1.0 || uboGlobal.contrast != 1.0) {
        vec3 hsv = srgbToHsv(srgb);
        hsv.y *= uboGlobal.saturation;
        hsv.z = 0.5 + (hsv.z - 0.5) * uboGlobal.contrast;
        srgb = hsvToSrgb(hsv);
    }
    srgb = colorBlindnessCompensation(srgb);
    #if APPLY_COLOR_FILTER
        srgb = applyColorFilter(srgb);
    #endif
    return srgb;
}

vec3 applyOutputCorrection(vec3 srgb) {
    srgb = pow(srgb, vec3(uboGlobal.gammaCorrection));

    #if WINDOWS_HDR_CORRECTION
        srgb = windowsHdrCorrection(srgb);
    #endif

    // Reduce color banding
    srgb += (hash12(gl_FragCoord.xy + uboGlobal.elapsedTime) - 0.5) / 255.0;

    return srgb;
}
