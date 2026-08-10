#version 330

#include IS_CUBEMAP
#include <uniforms/global.glsl>

in vec2 screenUV;
out vec4 FragColor;

#if IS_CUBEMAP
    uniform samplerCube skyboxCubemap;
#else
    uniform sampler2D skyboxTexture;
#endif

void main() {
    // Reconstruct a world-space view ray for this pixel from the global UBO's matrices,
    // the same way tiled_lighting_frag.glsl does, instead of tracking camera yaw/pitch separately.
    vec2 ndc = screenUV * 2.0 - 1.0;
    vec4 p = invProjectionMatrix * vec4(ndc, 1e-10, 1.0);
    vec3 dir = normalize((p.xyz / p.w) - cameraPos);

#if IS_CUBEMAP
    FragColor = texture(skyboxCubemap, dir);
#else
    // --- APPLY VERTICAL SHIFT HERE ---
    // Slightly offsetting the Y lookup pushes the panorama horizon downwards
    float shiftedY = dir.y - 0.12;

    // Map UV coordinates using our shifted position variable
    vec2 uv = vec2(atan(dir.x, -dir.z) / (2.0 * 3.14159265) + 0.5, 1.0 - (acos(clamp(shiftedY, -1.0, 1.0)) / 3.14159265));

    FragColor = texture(skyboxTexture, uv);
#endif
}
