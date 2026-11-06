#version 330

#include <uniforms/global.glsl>
#include <uniforms/world_views.glsl>
#include <utils/constants.glsl>

layout (location = 0) in vec3 vPosition;
layout (location = 3) in int vTextureFaceIdx;
layout (location = 6) in int vWorldViewId;
layout (location = 7) in ivec2 vSceneBase;

uniform isamplerBuffer textureFaces;

#include <scene_common.glsl>

void main() {
    SceneVertex sv = resolveSceneVertex(vPosition, vTextureFaceIdx, vSceneBase, vWorldViewId, true);
    int alphaBias = sv.faceAlphaBiasHsl[sv.vertex];

    vec4 clipPosition = uboGlobal.projectionMatrix * vec4(sv.worldPosition, 1.0);
    int depthBias = (alphaBias >> 16) & 0xFF;
    if (!uboGlobal.orthographicProjection)
        clipPosition.z += depthBias / 128.0;

    gl_Position = clipPosition;
}
