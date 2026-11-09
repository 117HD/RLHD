#version 330

// An item's model, placed on its icon by ItemIconModel, drawn into a cell of the item icon texture

layout (location = 0) in vec2 vPosition;
layout (location = 1) in float vColor;
layout (location = 2) in vec3 vTexturePlane;
layout (location = 3) in vec4 vFace;

uniform float margin;
// How much smaller and where an icon is drawn within its own, like a rune on the rune pouch
uniform vec3 placement;

out vec2 fPosition;
// At a sample the triangle covers, since a pixel's center can lie outside it and a color far past its corners can be another hue
centroid out float fColor;
flat out float fFlatColor;
noperspective centroid out vec3 fTexturePlane;
flat out vec4 fFace;

void main() {
    vec2 icon = (vPosition - 16) * placement.x + 16 + placement.yz;
    vec2 cell = (icon + margin) / (vec2(36, 32) + 2 * margin);
    gl_Position = vec4(cell * 2 - 1, 0, 1);
    fPosition = vPosition;
    fColor = vColor;
    fFlatColor = vColor;
    fTexturePlane = vTexturePlane;
    fFace = vFace;
}
