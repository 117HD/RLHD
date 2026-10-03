#version 330

uniform sampler2D maskTexture;
uniform vec2 fboSize;

in vec3 fColor;
in vec2 fLocalUv;
in vec2 fPixelOffset;

out vec4 FragColor;

void main() {
    vec2 fUv = fPixelOffset / fboSize + 0.5;
    float maskAlpha = 1.0 - texture(maskTexture, fUv).a;
    if (maskAlpha <= 0.0)
        discard;

    vec2 edgeDist = min(fLocalUv, 1.0 - fLocalUv);
    vec2 aaWidth = min(fwidth(fLocalUv), vec2(0.45)) + 1e-6;
    float coverage = min(
        smoothstep(0.0, aaWidth.x, edgeDist.x),
        smoothstep(0.0, aaWidth.y, edgeDist.y)
    );

    FragColor = vec4(fColor, coverage * maskAlpha);
}
