#pragma once

#include utils/constants.glsl

#if APPLY_COLOR_FILTER
uniform int colorFilterPrevious;
uniform int colorFilter;
uniform float colorFilterFade;

#define COLOR_FILTER_NONE 0
#define COLOR_FILTER_GREYSCALE 1
#define COLOR_FILTER_SEPIA 2
#define COLOR_FILTER_HIGH_CONTRAST 3
#define COLOR_FILTER_CARTOON 4
#define COLOR_FILTER_INVERT 5
#define COLOR_FILTER_BLACK_AND_WHITE 6

vec3 applySingleColorFilter(int filterIndex, vec3 color) {
    switch (filterIndex) {
        case COLOR_FILTER_GREYSCALE:
            return vec3(dot(color, vec3(0.2126, 0.7152, 0.0722)));
        case COLOR_FILTER_SEPIA:
            return color * mat3(
                0.393, 0.769, 0.189,
                0.349, 0.686, 0.168,
                0.272, 0.534, 0.131
            );
        case COLOR_FILTER_HIGH_CONTRAST: {
            float intensity = dot(color, vec3(0.2126, 0.7152, 0.0722));
            float modifier = 2.2;
            return vec3(
                intensity + (color.r - intensity) * modifier,
                intensity + (color.g - intensity) * modifier,
                intensity + (color.b - intensity) * modifier
            );
        }
        case COLOR_FILTER_CARTOON: {
            float quantizationLevels = 7.0;
            vec3 quantizedColor = floor(color * quantizationLevels) / quantizationLevels;
            return quantizedColor;
        }
        case COLOR_FILTER_INVERT:
            return 1 - color;
        case COLOR_FILTER_BLACK_AND_WHITE:
            return dot(color, vec3(0.2126, 0.7152, 0.0722)) > 0.4 ? vec3(1) : vec3(0);
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
