#pragma once

#if APPLY_COLOR_FILTER

#include <uniforms/global.glsl>

#include <utils/constants.glsl>

#define COLOR_FILTER_NONE 0
#define COLOR_FILTER_GREYSCALE 1
#define COLOR_FILTER_SEPIA 2
#define COLOR_FILTER_HIGH_CONTRAST 3
#define COLOR_FILTER_CARTOON 4
#define COLOR_FILTER_INVERT 5
#define COLOR_FILTER_BLACK_AND_WHITE 6
#define COLOR_FILTER_CEL_SHADING 7
#define COLOR_FILTER_RS3_HIGH_CONTRAST 8


#define ENTITY_PLAYER        0
#define ENTITY_NPC_FRIENDLY  1
#define ENTITY_HOSTILE   2
#define ENTITY_OBJECT        3
#define ENTITY_PROJECTILE    4
#define ENTITY_GRAPHICS      5
#define ENTITY_PLAYER_OTHER  6
#define ENTITY_GROUND_ITEMS  7
#define ENTITY_TERRAIN      15

vec3 applyRs3HighContrast(int category, vec3 color) {
    switch (category) {
        case ENTITY_TERRAIN:
            float l = dot(color, vec3(0.2126, 0.7152, 0.0722));
            return mix(vec3(l), color, 0.08);

        case ENTITY_PLAYER:
            return mix(color, highContrastColors[0], 0.75);

        case ENTITY_PLAYER_OTHER:
            return mix(color, highContrastColors[6], 0.8);

        case ENTITY_HOSTILE:
            return mix(color, highContrastColors[2], 0.8);

        case ENTITY_NPC_FRIENDLY:
            return mix(color, highContrastColors[1], 0.65);

        case ENTITY_OBJECT:
            return mix(color, highContrastColors[3], 0.7);

        case ENTITY_PROJECTILE:
            return mix(color, highContrastColors[4], 0.75);

        case ENTITY_GRAPHICS:
            return mix(color, highContrastColors[5], 0.6);

        case ENTITY_GROUND_ITEMS:
           return mix(color, highContrastColors[7], 0.6);

        default:
            return color;
    }
}

vec3 applySingleColorFilter(int filterIndex, vec3 color, int category) {
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
        case COLOR_FILTER_CEL_SHADING: {
            float intensity = dot(color, vec3(0.299, 0.587, 0.114));
            float quantizationLevels = 8.0;
            float quantizedIntensity = floor(intensity * quantizationLevels) / quantizationLevels;
            return color * quantizedIntensity / intensity;
        }
        case COLOR_FILTER_RS3_HIGH_CONTRAST:
            return applyRs3HighContrast(category, color);
        default:
            return color;
    }
}

vec3 applyColorFilter(vec3 color, int category) {
    vec3 previous = applySingleColorFilter(colorFilterPrevious, color, category);
    vec3 current = applySingleColorFilter(colorFilter, color, category);
    // Fade smoothly between the previous and current filters
    return mix(previous, current, smoothstep(0, 1, colorFilterFade));
}
#endif
