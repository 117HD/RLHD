#version 330

#include <uniforms/ui.glsl>

// Icons, how near their pixels are to them, and the slots' cells of what's behind and over them, see NativeItemIcons
uniform sampler2DArray itemIcons;
uniform sampler2D uiTexture;
// The first layer of how near pixels are to the icons, four icons to a layer
uniform int surroundingsLayer;
// The first layer of the slots' cells, how many lie side by side in a layer, and how many a layer holds
uniform ivec3 slotCells;

#include <scaling/ui_scaling.glsl>
#include <utils/color_blindness.glsl>
#include <utils/misc.glsl>

const ivec2 ICON_SIZE = ivec2(36, 32);
const ivec2 GRID_SIZE = ivec2(40, 36);

in vec2 fUv;
flat in vec2 fLayers;
flat in float fOpacity;
flat in float fBorder;
flat in vec4 fShadow;
flat in vec4 fFill;
flat in vec4 fOutline;

out vec4 FragColor;

float surroundingsAt(vec2 gridUv) {
    int icon = int(fLayers.x);
    return texture(itemIcons, vec3(gridUv, surroundingsLayer + icon / 4))[icon % 4];
}

vec4 slotTexel(ivec2 pixel, int cell) {
    int within = cell % slotCells.z;
    ivec2 origin = ivec2(within % slotCells.y, within / slotCells.y) * GRID_SIZE;
    return texelFetch(itemIcons, ivec3(origin + clamp(pixel, ivec2(0), GRID_SIZE - 1), slotCells.x + cell / slotCells.z), 0);
}

vec4 iconAt(vec2 gridUv) {
    vec4 icon = texture(itemIcons, vec3(gridUv, fLayers.x));
    // Selected items have a white border around their black one
    if (fBorder == 2)
        icon += surroundingsAt(gridUv) * (1 - icon.a);
    return icon;
}

vec4 otherOverlays(vec2 gridUv, ivec2 pixel, float item, float shadow) {
    vec4 elsewhere = vec4(0);
    if (all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, GRID_SIZE)))
        elsewhere = slotTexel(pixel, int(fLayers.y) + 1);

    float filled = max(item, shadow);
    float outlined = max(surroundingsAt(gridUv) - item, 0);
    vec4 redrawn = fFill * filled;
    redrawn += fOutline * outlined * (1 - redrawn.a);
    return elsewhere + redrawn * (1 - elsewhere.a);
}

void main() {
    vec2 uiUv = vec2(gl_FragCoord.x / uboUi.targetDimensions.x, 1 - gl_FragCoord.y / uboUi.targetDimensions.y);
    // Sampled like the UI pass, since scaling like xBR blends in the alpha of neighboring pixels too,
    // and clamped like the UI pass's output is, since cubic scaling overshoots
    float ui = clamp(textureUi(uiTexture, uiUv).a, 0, 1);

    vec2 grid = vec2(GRID_SIZE);
    vec2 gridPosition = fUv * vec2(ICON_SIZE) + (grid - vec2(ICON_SIZE)) / 2;
    vec2 gridUv = gridPosition / grid;
    vec2 gamePixel = 1 / grid;
    // The interface pixel is found from the screen position, alike for every item, so items next to each other meet exactly,
    // even where the screen's pixels lie right on the edge between two of the interface's
    vec2 uiPosition = uiUv * vec2(uboUi.sourceDimensions);
    ivec2 uiPixel = ivec2(floor(uiPosition));
    ivec2 pixel = uiPixel - ivec2(round(uiPosition - gridPosition));

    vec4 icon = iconAt(gridUv);
    float item = icon.a;
    float iconShadow = iconAt(gridUv - gamePixel).a;
    vec4 overlays = otherOverlays(gridUv, pixel, item, iconShadow);
    icon *= fOpacity;
    vec4 background = slotTexel(pixel, int(fLayers.y));
    // Only fill in what the stretched interface lets through, and around the item only what the interface covers,
    // since items next to it fill in their own cut out backgrounds. Within the item's own cut out area, what the
    // interface holds was drawn over the item afterwards, like Inventory Grid's slots, so the background stays under it.
    float covered = background.a;
    float drawnOver = 0;
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, GRID_SIZE)))
        covered = min(covered, texelFetch(uiTexture, uiPixel, 0).a);
    else
        drawnOver = texelFetch(uiTexture, uiPixel, 0).a;
    float shown = drawnOver + covered * (1 - drawnOver);
    background *= covered > 0 && shown > ui ? (shown - ui) / (background.a * (1 - ui)) : 0;

    // A see-through shadow, like the dragged item's, shades by its alpha. The game only shades around the item, which
    // matters where the item lets what's under it through
    float seen = 1 - item * fOpacity;
    float shadow = seen > 0 ? iconShadow * fShadow.a * (1 - item) / seen : 0;
    background = vec4(fShadow.rgb, 1) * shadow + background * (1 - shadow);

    vec4 c = icon + background * (1 - icon.a);
    c = overlays + c * (1 - overlays.a);
    c.rgb = colorBlindnessCompensation(c.rgb);

    #if WINDOWS_HDR_CORRECTION
        c.rgb = windowsHdrCorrection(c.rgb);
    #endif

    FragColor = c;
}
