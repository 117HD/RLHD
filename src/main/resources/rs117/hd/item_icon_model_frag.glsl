#version 330

#include <utils/color_utils.glsl>

// Shaded like the game shades item icons, see ItemIconModel

uniform sampler2DArray textures;
uniform float brightness;
// Where the game's own icon is kept in the last layer of textures, or -1
uniform int iconRow;

in vec2 fPosition;
centroid in float fColor;
flat in float fFlatColor;
noperspective centroid in vec3 fTexturePlane;
flat in vec4 fFace;

out vec4 FragColor;

// Parts of see-through textures smaller than one of the game's pixels, like sparkles, are only drawn where the game drew them,
// or they'd be buried in outline
bool isDrawnByGame() {
    ivec2 pixel = ivec2(floor(fPosition));
    return iconRow < 0 || any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, ivec2(36, 32))) ? iconRow < 0 :
        texelFetch(textures, ivec3(pixel.x, iconRow + pixel.y, textureSize(textures, 0).z - 1), 0).a > 0;
}

vec3 palette(int hsl) {
    // Rounded to 8 bits before the brightness is applied, like the game does
    vec3 rounded = floor(packedHslToSrgb(hsl) * 256) / 256;
    // 0 means nothing was drawn, so black is 1
    return max(clamp(floor(pow(rounded, vec3(brightness)) * 256), 0, 255), vec3(0, 0, 1)) / 255;
}

void main() {
    int alpha = int(fFace.x);
    int textureLayer = int(fFace.y);
    bool flatShaded = (int(fFace.w) & 1) != 0;
    vec3 rgb;
    if (textureLayer < 0) {
        rgb = palette(flatShaded ? int(fFlatColor) : clamp(int(fColor), 0, 0xFFFF));
    } else {
        // Texture coordinates come from the plane through the texture's three vertices, like in the game
        int size = int(fFace.z);
        vec2 uv = fTexturePlane.xy / fTexturePlane.z;
        ivec2 texel = ivec2(clamp(int(floor(uv.x * size)), 0, size - 1), int(mod(floor(uv.y * size), size)));
        vec4 c = texelFetch(textures, ivec3(texel, textureLayer), 0);
        if (c == vec4(0) || (int(fFace.w) & 2) != 0 && !isDrawnByGame())
            discard;
        int shade = int(flatShaded ? fFlatColor : fColor) * 2;
        ivec3 shaded = ivec3(round(c.rgb * 255)) * shade >> 8;
        rgb = max(vec3(shaded), vec3(0, 0, 1)) / 255;
    }
    FragColor = vec4(rgb, (256 - alpha) / 256.);
}
