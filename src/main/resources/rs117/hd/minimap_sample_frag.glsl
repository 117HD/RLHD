#version 330

uniform sampler2D cacheTexture;
uniform sampler2D maskTexture;
uniform vec2 fboSize;
uniform float cosYaw;
uniform float sinYaw;
uniform float pixelToWorld;
uniform vec2 cacheCenterUv;
uniform float cacheUvPerWorldUnit;

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec2 pixelOffset = (fUv - 0.5) * fboSize;
    float rx = pixelOffset.x;
    float ry = -pixelOffset.y;

    float dx = (rx * cosYaw + ry * sinYaw) * pixelToWorld;
    float dz = (rx * sinYaw - ry * cosYaw) * pixelToWorld;

    float maskAlpha = 1.0 - texture(maskTexture, fUv).a;
    if (maskAlpha <= 0) {
        FragColor = vec4(0);
        return;
    }

    vec2 cacheUv = cacheCenterUv + vec2(dx, dz) * cacheUvPerWorldUnit;
    if (cacheUv.x < 0 || cacheUv.x > 1 || cacheUv.y < 0 || cacheUv.y > 1) {
        FragColor = vec4(0);
        return;
    }

    vec4 cacheColor = texture(cacheTexture, cacheUv);
    FragColor = vec4(cacheColor.rgb, maskAlpha * cacheColor.a);
}
