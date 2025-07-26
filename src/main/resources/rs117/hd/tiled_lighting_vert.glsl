#version 330

#include <uniforms/global.glsl>

out vec3 fRay;

void main() {
    float x = -1.0 + float((gl_VertexID & 1) << 2);
    float y = -1.0 + float((gl_VertexID & 2) << 1);
    gl_Position = vec4(x, y, 0, 1);

    vec2  uv = vec2((x+1.0)*0.5, (y+1.0)*0.5);
    vec2 ndcUv = (uv * 2 - 1) * vec2(1, 1);
    const float eps = 1e-10;
    vec4 farPos = invProjectionMatrix * vec4(ndcUv, eps, 1);
    fRay = farPos.xyz / farPos.w;
}
