#version 330

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>
#include <utils/constants.glsl>

layout (location = 0) in vec3 vPosition;

layout (location = 7) in ivec2 vSceneBase;
layout (location = 6) in int vWorldViewId;

void main() {
    vec3 sceneOffset = vec3(vSceneBase.x, 0, vSceneBase.y);
    vec3 worldPosition = sceneOffset + vPosition;
    if (vWorldViewId != -1) {
        mat4x3 worldViewProjection = mat4x3(getWorldViewProjection(vWorldViewId));
        worldPosition = worldViewProjection * vec4(worldPosition, 1.0);
    }

    gl_Position = projectionMatrix * vec4(worldPosition, 1.0);
}
