#pragma once

vec3 getFilter(int index, vec3 color) {
    if (index == 1) {
        return vec3(dot(color, vec3(0.2126, 0.7152, 0.0722)));
    } else if (index == 2) {
        return vec3(
            dot(color, vec3(0.393, 0.769, 0.189)),
            dot(color, vec3(0.349, 0.686, 0.168)),
            dot(color, vec3(0.272, 0.534, 0.131))
        );
    } else if (index == 3) {
        float intensity = dot(color, vec3(0.2126, 0.7152, 0.0722));
        float modifier = 2.2;
        return vec3(
            intensity + (color.r - intensity) * modifier,
            intensity + (color.g - intensity) * modifier,
            intensity + (color.b - intensity) * modifier
        );
    } else if(index == 4) {
       float threshold = 0.5;
       float smoothness = 0.2;
       vec3 shadedColor = smoothstep(threshold - smoothness, threshold + smoothness, color);
       return mix(color, shadedColor, 0.6);
    } else if(index == 5) {

        float quantizationLevels = 7.0;
        vec3 quantizedColor = floor(color * quantizationLevels) / quantizationLevels;
        return quantizedColor;
    }
    return color;
}