#version 330

#include <uniforms/global.glsl>
#include <uniforms/sky.glsl>

#include <utils/output_transform.glsl>
#include <utils/sky_sampling.glsl>

in vec2 fScreenPos;

out vec4 FragColor;

void main() {
    vec3 color = uboGlobal.fogColor;

    if (uboSky.enabled && !uboGlobal.orthographicProjection) {
        // Unproject a near/far ray to get the view direction.
        vec4 nearWorld = uboGlobal.invProjectionMatrix * vec4(fScreenPos, -1.0, 1.0);
        vec4 farWorld = uboGlobal.invProjectionMatrix * vec4(fScreenPos, 1.0, 1.0);
        vec3 viewDir = normalize(farWorld.xyz / farWorld.w - nearWorld.xyz / nearWorld.w);
        color = sampleSky(viewDir, true, true);
    }

    vec3 srgb = linearToSrgb(color);
    srgb = applyColorAdjustments(srgb);
    srgb = applyOutputCorrection(srgb);
    FragColor = vec4(srgb, 1.0);
}
