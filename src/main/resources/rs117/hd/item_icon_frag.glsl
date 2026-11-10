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

// The game writes the stack size at the top of the icon, a pixel wide, which is drawn as smooth strokes through its pixels
// instead of stretched: how far a place in the grid is from them, and the color of the nearest
const float STACK_SIZE_ROWS = 18;
const float STACK_SIZE_RADIUS = .5;

// Its pixels lie in the cell of what's drawn over the item, as colors without alpha
bool stackSizeTexel(ivec2 pixel, out vec3 color) {
    vec4 texel = slotTexel(pixel, int(fLayers.y) + 1);
    color = texel.rgb;
    return texel.a == 0 && texel.rgb != vec3(0);
}

float stackSizeDistance(vec2 position, inout vec3 color) {
    ivec2 nearest = ivec2(floor(position));
    float best = 4;
    vec3 texel, unused;
    for (int i = 0; i < 9; i++) {
        ivec2 a = nearest + ivec2(i % 3 - 1, i / 3 - 1);
        if (!stackSizeTexel(a, texel))
            continue;
        vec2 d = position - (vec2(a) + .5);
        float nearestSq = dot(d, d);
        // Pixels next to each other are joined, diagonally only where they aren't around a corner already.
        // Each looks after its own half of the stroke.
        for (int n = 0; n < 9; n++) {
            ivec2 o = ivec2(n % 3 - 1, n / 3 - 1);
            if (n == 4 || !stackSizeTexel(a + o, unused))
                continue;
            if (o.x != 0 && o.y != 0 && (stackSizeTexel(a + ivec2(o.x, 0), unused) || stackSizeTexel(a + ivec2(0, o.y), unused)))
                continue;
            vec2 e = d - vec2(o) * clamp(dot(d, vec2(o)) / dot(vec2(o), vec2(o)), 0, .5);
            nearestSq = min(nearestSq, dot(e, e));
        }
        if (nearestSq < best) {
            best = nearestSq;
            color = texel;
        }
    }
    return sqrt(best);
}

// With its black shadow a pixel down and right, like the game's
vec4 stackSizeAt(vec2 gridPosition, float pixelWidth) {
    if (gridPosition.y > STACK_SIZE_ROWS)
        return vec4(0);
    vec3 color = vec3(0);
    float text = clamp((STACK_SIZE_RADIUS - stackSizeDistance(gridPosition, color)) / pixelWidth + .5, 0, 1);
    vec3 unused;
    float shadow = clamp((STACK_SIZE_RADIUS - stackSizeDistance(gridPosition - 1, unused)) / pixelWidth + .5, 0, 1);
    return vec4(color * text, text + shadow * (1 - text));
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
    if (elsewhere.a == 0)
        elsewhere = vec4(0);

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
    // The stack size is part of the game's icon, so a see-through item's is just as see-through, and hides the item under it
    vec4 stackSize = stackSizeAt(gridPosition, max(fwidth(gridPosition.x), fwidth(gridPosition.y)));
    icon = stackSize + icon * (1 - stackSize.a);
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
