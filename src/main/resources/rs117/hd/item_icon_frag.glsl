#version 330

#include <uniforms/ui.glsl>

uniform sampler2DArray itemIcons;
uniform sampler2DArray itemIconSurroundings;
uniform sampler2DArray itemBackgrounds;
uniform sampler2D uiTexture;

#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>

#if UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
    #include <scaling/hybrid.glsl>
#endif

const ivec2 ICON_SIZE = ivec2(36, 32);

in vec2 fUv;
flat in vec2 fLayers;
flat in float fOpacity;
flat in vec4 fShadow;
flat in vec4 fFill;
flat in vec4 fOutline;

out vec4 FragColor;

ivec2 gridSize() {
    return textureSize(itemBackgrounds, 0).xy;
}

vec4 otherOverlays(vec2 gridUv, float item, float shadow) {
    vec4 elsewhere = vec4(0);
    ivec2 pixel = ivec2(floor(gridUv * vec2(gridSize())));
    if (all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, gridSize())))
        elsewhere = texelFetch(itemBackgrounds, ivec3(pixel, fLayers.y + 1), 0);

    float filled = max(item, shadow);
    float outlined = max(texture(itemIconSurroundings, vec3(gridUv, fLayers.x)).r - item, 0);
    vec4 redrawn = fFill * filled;
    redrawn += fOutline * outlined * (1 - redrawn.a);
    return elsewhere + redrawn * (1 - elsewhere.a);
}

void main() {
    vec2 uiUv = vec2(gl_FragCoord.x / targetDimensions.x, 1 - gl_FragCoord.y / targetDimensions.y);
    #if UI_SCALING_MODE == UI_SCALING_MODE_HYBRID
        float ui = textureHybrid(uiTexture, uiUv).a;
    #else
        float ui = texture(uiTexture, uiUv).a;
    #endif

    vec2 grid = vec2(gridSize());
    vec2 gridUv = (fUv * vec2(ICON_SIZE) + (grid - vec2(ICON_SIZE)) / 2) / grid;
    vec2 gamePixel = 1 / grid;

    vec4 icon = texture(itemIcons, vec3(gridUv, fLayers.x));
    float iconShadow = texture(itemIcons, vec3(gridUv - gamePixel, fLayers.x)).a;
    vec4 overlays = otherOverlays(gridUv, icon.a, iconShadow);
    icon *= fOpacity;
    vec4 background = texture(itemBackgrounds, vec3(gridUv, fLayers.y));
    // Only fill in what the stretched interface lets through, and around the item only what the interface covers,
    // since items next to it fill in their own cut out backgrounds
    float covered = background.a;
    if (any(lessThan(gridUv, vec2(0))) || any(greaterThanEqual(gridUv, vec2(1))))
        covered = min(covered, texelFetch(uiTexture, ivec2(uiUv * vec2(sourceDimensions)), 0).a);
    background *= covered > ui ? (covered - ui) / (background.a * (1 - ui)) : 0;

    float shadow = iconShadow * fShadow.a;
    background = fShadow * shadow + background * (1 - shadow);

    vec4 c = icon + background * (1 - icon.a);
    c = overlays + c * (1 - overlays.a);
    c.rgb = colorBlindnessCompensation(c.rgb);

    #if WINDOWS_HDR_CORRECTION
        c.rgb = windowsHdrCorrection(c.rgb);
    #endif

    FragColor = c;
}
