#version 330

#include <uniforms/ui.glsl>

uniform sampler2DArray itemIcons;
uniform sampler2DArray itemIconSurroundings;
uniform sampler2DArray itemBackgrounds;
uniform sampler2D uiTexture;

#include <scaling/ui_scaling.glsl>
#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>

const ivec2 ICON_SIZE = ivec2(36, 32);

in vec2 fUv;
flat in vec2 fLayers;
flat in float fOpacity;
flat in float fBorder;
flat in vec4 fShadow;
flat in vec4 fFill;
flat in vec4 fOutline;

out vec4 FragColor;

ivec2 gridSize() {
    return textureSize(itemBackgrounds, 0).xy;
}

vec4 iconAt(vec2 gridUv) {
    vec4 icon = texture(itemIcons, vec3(gridUv, fLayers.x));
    // Selected items have a white border around their black one
    if (fBorder == 2)
        icon += texture(itemIconSurroundings, vec3(gridUv, fLayers.x)).r * (1 - icon.a);
    return icon;
}

vec4 otherOverlays(vec2 gridUv, ivec2 pixel, float item, float shadow) {
    vec4 elsewhere = vec4(0);
    if (all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, gridSize())))
        elsewhere = texelFetch(itemBackgrounds, ivec3(pixel, fLayers.y + 1), 0);

    float filled = max(item, shadow);
    float outlined = max(texture(itemIconSurroundings, vec3(gridUv, fLayers.x)).r - item, 0);
    vec4 redrawn = fFill * filled;
    redrawn += fOutline * outlined * (1 - redrawn.a);
    return elsewhere + redrawn * (1 - elsewhere.a);
}

void main() {
    vec2 uiUv = vec2(gl_FragCoord.x / uboUi.targetDimensions.x, 1 - gl_FragCoord.y / uboUi.targetDimensions.y);
    // Sampled like the UI pass, since scaling like xBR blends in the alpha of neighboring pixels too,
    // and clamped like the UI pass's output is, since cubic scaling overshoots
    float ui = clamp(textureUi(uiTexture, uiUv).a, 0, 1);

    vec2 grid = vec2(gridSize());
    vec2 gridPosition = fUv * vec2(ICON_SIZE) + (grid - vec2(ICON_SIZE)) / 2;
    vec2 gridUv = gridPosition / grid;
    vec2 gamePixel = 1 / grid;
    // The interface pixel is found from the screen position, alike for every item, so items next to each other meet exactly,
    // even where the screen's pixels lie right on the edge between two of the interface's
    vec2 uiPosition = uiUv * vec2(uboUi.sourceDimensions);
    ivec2 uiPixel = ivec2(floor(uiPosition));
    ivec2 pixel = uiPixel - ivec2(round(uiPosition - gridPosition));

    vec4 icon = iconAt(gridUv);
    float iconShadow = iconAt(gridUv - gamePixel).a;
    vec4 overlays = otherOverlays(gridUv, pixel, icon.a, iconShadow);
    icon *= fOpacity;
    vec4 background = texelFetch(itemBackgrounds, ivec3(clamp(pixel, ivec2(0), gridSize() - 1), fLayers.y), 0);
    // Only fill in what the stretched interface lets through, and around the item only what the interface covers,
    // since items next to it fill in their own cut out backgrounds
    float covered = background.a;
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, gridSize())))
        covered = min(covered, texelFetch(uiTexture, uiPixel, 0).a);
    background *= covered > ui ? (covered - ui) / (background.a * (1 - ui)) : 0;

    // A see-through shadow, like the dragged item's, shades by its alpha
    float shadow = iconShadow * fShadow.a;
    background = vec4(fShadow.rgb, 1) * shadow + background * (1 - shadow);

    vec4 c = icon + background * (1 - icon.a);
    c = overlays + c * (1 - overlays.a);
    c.rgb = colorBlindnessCompensation(c.rgb);

    #if WINDOWS_HDR_CORRECTION
        c.rgb = windowsHdrCorrection(c.rgb);
    #endif

    FragColor = c;
}
