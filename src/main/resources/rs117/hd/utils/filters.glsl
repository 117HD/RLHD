#pragma once

uniform int colorFilterPrevious;
uniform int colorFilter;
uniform float colorFilterFadeDuration;

#define NONE 0
#define GREYSCALE 1
#define SEPIA 2
#define HIGH_CONTRAST 3
#define CARTOON 4
#define INVERT 5
#define BLACK_AND_WHITE 6
#define NO_IDEA 7

vec3 getFilter(int index, vec3 color) {
    if (index == GREYSCALE) {
        return vec3(dot(color, vec3(0.2126, 0.7152, 0.0722)));
    } else if (index == SEPIA) {
        return vec3(
            dot(color, vec3(0.393, 0.769, 0.189)),
            dot(color, vec3(0.349, 0.686, 0.168)),
            dot(color, vec3(0.272, 0.534, 0.131))
        );
    } else if (index == HIGH_CONTRAST) {
        float intensity = dot(color, vec3(0.2126, 0.7152, 0.0722));
        float modifier = 2.2;
        return vec3(
            intensity + (color.r - intensity) * modifier,
            intensity + (color.g - intensity) * modifier,
            intensity + (color.b - intensity) * modifier
        );
    } else if(index == CARTOON) {
        float quantizationLevels = 7.0;
        vec3 quantizedColor = floor(color * quantizationLevels) / quantizationLevels;
        return quantizedColor;
    } else if(index == INVERT) {
        return vec3(1.0) - color;
    } else if(index == BLACK_AND_WHITE) {
        float threshold = 0.4;
        vec3 shadedColor = vec3(0.0);

        if (dot(color, vec3(0.2126, 0.7152, 0.0722)) > threshold) {
               shadedColor = vec3(1.0);
        }

        return mix(vec3(0.0), vec3(1.0), shadedColor);
    }
    return color;
}

vec3 applyFilter(vec3 color) {
    if(colorFilter == NONE) {
        return color;
    }

    vec3 filteredColor = color;

    vec3 previousFilteredColor = getFilter(colorFilterPrevious, color);
    vec3 newFilteredColor = getFilter(colorFilter, color);

    float fadeMilliseconds = colorFilterFadeDuration;

    // Convert fadeMilliseconds to 0-1 range
    float fadeAmount = clamp(fadeMilliseconds / 4000.0, 0.0, 1.0);

    // Smooth out the fadeAmount using cubic interpolation
    float smoothedFade = smoothstep(0.0, 1.0, fadeAmount);
    smoothedFade = smoothstep(0.0, 1.0, smoothedFade);

    // Interpolate between old and new filter types based on fade progress
    filteredColor = mix(previousFilteredColor, newFilteredColor, 1.0 - smoothedFade); // Invert smoothedFade for correct fading

    return filteredColor;
}
