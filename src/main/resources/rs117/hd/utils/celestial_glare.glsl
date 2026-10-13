#pragma once

#include <uniforms/sky.glsl>
#include <utils/color_utils.glsl>

// Inputs are composited display-linear colors, after any selective tone mapping.
// Reduce the added perceptual brightness, keeping the underlying background intact.
vec3 reduceCelestialGlare(vec3 color, vec3 background) {
    #if GLARE_INTENSITY
        if (linearSrgbLuminance(color - background) <= 0.0)
            return color;
        // Match the existing display clipping so out-of-range HDR cannot defeat the reduction.
        color = clamp(color, 0.0, 1.0);
        background = clamp(background, 0.0, 1.0);
        float baseLuminance = max(0.0, linearSrgbLuminance(background));
        float luminance = linearSrgbLuminance(color);
        if (luminance <= baseLuminance)
            return color;
        float target = srgbToLinear(mix(linearToSrgb(baseLuminance), linearToSrgb(luminance),
            uboSky.glareIntensity));
        float amount = clamp((target - baseLuminance) / max(luminance - baseLuminance, 1e-7), 0.0, 1.0);
        return mix(background, color, amount);
    #else
        return color;
    #endif
}
