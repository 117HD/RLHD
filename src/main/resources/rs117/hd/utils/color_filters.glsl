#pragma once

#if APPLY_COLOR_FILTER

#include <uniforms/global.glsl>

#include <utils/constants.glsl>
#include <utils/color_utils.glsl>

#define COLOR_FILTER_NONE 0
#define COLOR_FILTER_GREYSCALE 1
#define COLOR_FILTER_SEPIA 2
#define COLOR_FILTER_HIGH_CONTRAST 3
#define COLOR_FILTER_CARTOON 4
#define COLOR_FILTER_INVERT 5
#define COLOR_FILTER_BLACK_AND_WHITE 6
#define COLOR_FILTER_CEL_SHADING 7

vec3 applySingleColorFilter(int filterIndex, vec3 color) {
    switch (filterIndex) {
        case COLOR_FILTER_GREYSCALE:
            return vec3(linearSrgbLuminance(color));
        case COLOR_FILTER_SEPIA:
            return color * mat3(
                0.393, 0.769, 0.189,
                0.349, 0.686, 0.168,
                0.272, 0.534, 0.131
            );
        case COLOR_FILTER_HIGH_CONTRAST: {
            vec3 srgbColor = linearToSrgb(max(color, 0.0));
            float intensity = linearToSrgb(max(linearSrgbLuminance(color), 0.0));
            // Only apply the full boost in daylight. At night, lightStrength drops
            // towards 0, so the modifier relaxes towards 1 (no contrast change) instead
            // of exaggerating night's blue ambient tint into a wall of pure blue.
            float modifier = mix(1.0, 2.2, clamp(lightStrength, 0.0, 1.0));
            vec3 srgbResult = intensity + (srgbColor - intensity) * modifier;
            return srgbToLinear(clamp(srgbResult, 0.0, 1.0));
        }
        case COLOR_FILTER_CARTOON: {
            float quantizationLevels = 7.0;
            vec3 srgbColor = linearToSrgb(max(color, 0.0));
            vec3 quantizedSrgbColor = floor(srgbColor * quantizationLevels) / quantizationLevels;
            return srgbToLinear(quantizedSrgbColor);
        }
        case COLOR_FILTER_INVERT:
            return srgbToLinear(1 - linearToSrgb(max(color, 0.0)));
        case COLOR_FILTER_BLACK_AND_WHITE:
            return linearToSrgb(max(linearSrgbLuminance(color), 0.0)) > 0.4 ? vec3(1) : vec3(0);
        case COLOR_FILTER_CEL_SHADING: {
            vec3 srgbColor = linearToSrgb(max(color, 0.0));
            float perceptual = linearToSrgb(max(linearSrgbLuminance(color), 0.0));
            float quantizationLevels = 8.0;
            float quantizedPerceptual = floor(perceptual * quantizationLevels + 0.5) / quantizationLevels;
            vec3 srgbResult = srgbColor - (perceptual - quantizedPerceptual);
            return srgbToLinear(clamp(srgbResult, 0.0, 1.0));
        }
        default:
            return color;
    }
}

vec3 applyColorFilter(vec3 color) {
    vec3 previous = applySingleColorFilter(colorFilterPrevious, color);
    vec3 current = applySingleColorFilter(colorFilter, color);
    // Fade smoothly between the previous and current filters
    return mix(previous, current, smoothstep(0, 1, colorFilterFade));
}
#endif
