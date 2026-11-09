#version 330

// The finished icon from its drawn layers, like a note's paper with the item on it: each with the game's black outline,
// and how near each pixel is to the icon, which the white outline of selected items and other plugins' outlines use

uniform sampler2DArray layers;
uniform int layerCount;
uniform ivec2 borders;
// Screen pixels per game pixel
uniform vec2 scale;

layout (location = 0) out vec4 icon;
layout (location = 1) out vec4 surroundings;

// How far a pixel is from the layer's shape, in game pixels, up to a limit. A partly covered pixel's edge
// is taken to lie as far past its center as its coverage is past half.
float distanceTo(int layer, ivec2 pixel, float limit) {
    ivec2 size = textureSize(layers, 0).xy;
    ivec2 reach = ivec2(ceil(limit * scale)) + 1;
    float screenPixel = 1 / min(scale.x, scale.y);
    float result = limit + 1;
    for (int y = -reach.y; y <= reach.y; y++) {
        for (int x = -reach.x; x <= reach.x; x++) {
            ivec2 p = pixel + ivec2(x, y);
            if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size)))
                continue;
            float coverage = texelFetch(layers, ivec3(p, layer), 0).a;
            if (coverage > 0)
                result = min(result, length(vec2(x, y) / scale) - (coverage - .5) * screenPixel);
        }
    }
    return result;
}

// How much of a pixel lies within a distance of the shape, with edges one screen pixel soft
float within(float distance, float radius) {
    return clamp((radius - distance) * min(scale.x, scale.y) + .5, 0, 1);
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 c = vec4(0);
    float around = 0;
    for (int i = 0; i < layerCount; i++) {
        vec4 drawn = texelFetch(layers, ivec3(pixel, i), 0);
        bool outlined = borders[i] > 0;
        float distance = distanceTo(i, pixel, outlined ? 2 : 1);
        // The game's black outline is one of its pixels wide
        if (outlined)
            drawn.a += within(distance, 1) * (1 - drawn.a);
        c = drawn + c * (1 - drawn.a);
        around = max(around, within(distance, outlined ? 2 : 1));
    }
    icon = c;
    surroundings = vec4(around);
}
