#version 330

// The samples of a drawn model as coverage and color, where black samples count as drawn, like the game's 0 means nothing

uniform sampler2DMSArray samples;
uniform int layer;
uniform int sampleCount;

out vec4 FragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec3 sum = vec3(0);
    float drawn = 0;
    for (int i = 0; i < sampleCount; i++) {
        vec3 c = texelFetch(samples, ivec3(pixel, layer), i).rgb;
        if (any(greaterThan(c, vec3(.5 / 255)))) {
            sum += c;
            drawn++;
        }
    }
    FragColor = vec4(sum, drawn) / sampleCount;
}
