#version 330

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>

#include <utils/constants.glsl>

layout (location = 0) in vec3 vPosition;
layout (location = 3) in int vTextureFaceIdx;
layout (location = 6) in int vWorldViewId;
layout (location = 7) in ivec2 vSceneBase;

 void main() {
    vec3 sceneOffset = vec3(vSceneBase.x, 0, vSceneBase.y);
    vec3 worldPosition = sceneOffset + vPosition;
    if (vWorldViewId != -1) {
        mat4x3 worldViewProjection = mat4x3(getWorldViewProjection(vWorldViewId));
        worldPosition = worldViewProjection * vec4(worldPosition, 1.0);
    }

    vec4 clipPosition = projectionMatrix * vec4(worldPosition, 1.0);
    int depthBias = (vTextureFaceIdx >> 24) & 0xff;
    clipPosition.z += depthBias * (1.0 / 128.0);

    gl_Position = clipPosition;
}