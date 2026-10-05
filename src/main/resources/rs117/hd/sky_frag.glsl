#version 330

#include <utils/output_transform.glsl>
#include <utils/sky_sampling.glsl>

in vec2 fScreenPos;

out vec4 FragColor;

void main() {
    vec3 color = fogColor;

    if (uboSky.enabled && !orthographicProjection) {
        // Unproject a near/far ray to get the view direction.
        vec4 nearWorld = invProjectionMatrix * vec4(fScreenPos, -1.0, 1.0);
        vec4 farWorld = invProjectionMatrix * vec4(fScreenPos, 1.0, 1.0);
        vec3 viewDir = normalize(farWorld.xyz / farWorld.w - nearWorld.xyz / nearWorld.w);
        color = sampleSky(viewDir, true, true);
    }

    vec3 srgb = linearToSrgb(color);
    srgb = applyColorAdjustments(srgb);
    srgb = applyOutputCorrection(srgb);
    // Reduce color banding
    srgb += (hash12(gl_FragCoord.xy + elapsedTime) - 0.5) / 255.0;
    FragColor = vec4(srgb, 1.0);
}
