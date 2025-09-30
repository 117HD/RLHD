// Anti-aliased UI scaling that respects pixel sharpness
// Approach taken from https://colececil.dev/blog/2017/scaling-pixel-art-without-destroying-it/
vec4 texturePixel(sampler2D tex, vec2 uv) {
    uv *= sourceDimensions;
    vec2 texelUv = fract(uv);
    vec2 pixelsPerTexel = vec2(targetDimensions) / vec2(sourceDimensions);
    vec2 interpolationAmount = min(texelUv * pixelsPerTexel, .5) - min((1 - texelUv) * pixelsPerTexel, .5);
    return texture(tex, (floor(uv) + .5 + interpolationAmount) / sourceDimensions);
}
