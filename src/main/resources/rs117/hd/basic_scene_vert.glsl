#version 330

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>
#include <utils/constants.glsl>

uniform float scale;

layout (location = 0) in vec3 vPosition;
layout (location = 2) in vec4 vNormal;

layout (location = 7) in ivec2 vSceneBase;
layout (location = 6) in int vWorldViewId;

void main() {
    int vertex = gl_VertexID % 3;

    vec3 sceneOffset = vec3(vSceneBase.x, 0, vSceneBase.y);
    vec3 worldPosition = sceneOffset + vPosition + (normalize(vNormal.xyz) * scale);
    vec3 worldNormal = vNormal.xyz;
    if (vWorldViewId != -1) {
        mat4x3 worldViewProjection = mat4x3(getWorldViewProjection(vWorldViewId));
        worldPosition = worldViewProjection * vec4(worldPosition, 1.0);
        worldNormal = mat3(worldViewProjection) * worldNormal;
    }

    gl_Position = projectionMatrix * vec4(worldPosition, 1.0);
}
