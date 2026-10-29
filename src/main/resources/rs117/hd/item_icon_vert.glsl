#version 330

layout (location = 0) in vec2 vPos;
layout (location = 1) in vec2 vUv;
layout (location = 2) in vec2 vLayers;
layout (location = 3) in float vOpacity;
layout (location = 4) in float vBorder;
layout (location = 5) in vec4 vShadow;
layout (location = 6) in vec4 vFill;
layout (location = 7) in vec4 vOutline;

out vec2 fUv;
flat out vec2 fLayers;
flat out float fOpacity;
flat out float fBorder;
flat out vec4 fShadow;
flat out vec4 fFill;
flat out vec4 fOutline;

void main() {
    gl_Position = vec4(vPos, 0, 1);
    fUv = vUv;
    fLayers = vLayers;
    fOpacity = vOpacity;
    fBorder = vBorder;
    fShadow = vShadow;
    fFill = vFill;
    fOutline = vOutline;
}
