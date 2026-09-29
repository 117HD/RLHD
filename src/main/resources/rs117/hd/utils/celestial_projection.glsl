#pragma once

#include <uniforms/global.glsl>

vec3 celestialViewDirection(vec3 viewDir, vec3 center) {
    vec4 centerClip = projectionMatrix * vec4(center, 0.0);
    vec4 viewClip = projectionMatrix * vec4(viewDir, 0.0);
    if (centerClip.w > 0.001 && viewClip.w > 0.001) {
        // Preserve the projected center and local area, but make the disk round.
        mat3 cameraAxes = transpose(mat3(viewMatrix));
        vec3 right = cameraAxes[0] - center * dot(cameraAxes[0], center);
        right = normalize(right);
        vec3 up = normalize(cross(center, right));
        if (dot(up, cameraAxes[1]) < 0.0)
            up = -up;

        mat4 projectionRows = transpose(projectionMatrix);
        vec2 scale = vec2(length(projectionRows[0].xyz), length(projectionRows[1].xyz));
        vec2 centerNdc = centerClip.xy / centerClip.w;
        vec4 rightClip = projectionMatrix * vec4(right, 0.0);
        vec4 upClip = projectionMatrix * vec4(up, 0.0);
        vec2 dx = (rightClip.xy - centerNdc * rightClip.w) / (centerClip.w * scale);
        vec2 dy = (upClip.xy - centerNdc * upClip.w) / (centerClip.w * scale);
        // The square root of the projection's area scale gives an isotropic
        // magnification: retain enlargement without its directional stretch.
        float magnification = sqrt(max(abs(dx.x * dy.y - dx.y * dy.x), 1e-8));
        vec2 offset = (viewClip.xy / viewClip.w - centerNdc) / (scale * magnification);
        return normalize(center + right * offset.x + up * offset.y);
    }
    return viewDir;
}
