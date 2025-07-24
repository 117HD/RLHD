#version 330

#include <uniforms/global.glsl>

uniform isampler2DArray tiledLightingArray;

#include <utils/constants.glsl>

in vec2 fUv;

out vec4 FragColor;

void main() {
    vec2 uv = fUv;
    uv.y = 1 - uv.y;

    vec4 c = vec4(0);

    #if MAX_LIGHTS_PER_TILE > 0
        vec2 screenUV = uv;
        vec2 tileCount = vec2(tileCountX, tileCountY);
        ivec2 tileXY = ivec2(floor(screenUV * tileCount));

        int tiledLightCount = 0;
        for (int idx = 0; idx < MAX_LIGHTS_PER_TILE; idx++) {
            int lightIdx = texelFetch(tiledLightingArray, ivec3(tileXY, idx), 0).r;
            if (lightIdx <= 0)
                break;

            lightIdx--;
            tiledLightCount++;
        }

        if (tiledLightCount > 0) {
            float level = (tiledLightCount / float(MAX_LIGHTS_PER_TILE)) * 3.14159265 / 2.0;
            c = vec4(sin(level), sin(level * 2), cos(level), 0.5);
        }
    #endif

    FragColor = c;
}
