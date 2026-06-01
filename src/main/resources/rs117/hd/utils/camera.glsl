#pragma once

struct Camera {
    vec3 position;

    float nearPlane;
    float farPlane;

    int  isReverseZ;
    ivec2 viewport;

    mat4 viewMatrix;
    mat4 projMatrix;
    mat4 viewProjMatrix;
    mat4 invViewProjMatrix;
};

bool Camera_isInfiniteFar(const Camera cam) {
    return cam.farPlane == 0.0; // TODO: isReverseZ should become flags
}

bool Camera_isReverseZ(const Camera cam) {
    return cam.isReverseZ != 0;
}

bool Camera_isStandard(const Camera cam) {
    return !Camera_isReverseZ(cam) && !Camera_isInfiniteFar(cam);
}

bool Camera_isPerspective(const Camera cam) {
    return cam.viewProjMatrix[2][3] != 0; // TODO: isReverseZ should become flags;
}

bool Camera_isOrthographic(const Camera cam) {
    return cam.viewProjMatrix[2][3] == 0; // TODO: isReverseZ should become flags
}

vec3 Camera_getForward(const Camera cam) {
    return vec3(cam.viewMatrix[2]);
}

vec3 Camera_getRight(const Camera cam) {
    return vec3(cam.viewMatrix[0]);
}

vec3 Camera_getUp(const Camera cam) {
    return vec3(cam.viewMatrix[1]);
}

float Camera_lineariseDepth(const Camera cam, float depth) {
    if (Camera_isReverseZ(cam)) {
        if (Camera_isInfiniteFar(cam))
            return cam.nearPlane / depth;

        return (cam.nearPlane * cam.farPlane) / (cam.nearPlane - depth * (cam.nearPlane - cam.farPlane));
    }

    if (Camera_isInfiniteFar(cam))
        return cam.nearPlane / (1.0 - depth);

    return (cam.nearPlane * cam.farPlane) / (cam.farPlane - depth * (cam.farPlane - cam.nearPlane));
}

vec3 Camera_reconstructViewPos(const Camera cam, vec2 uv, float depth) {
    // NDC in [-1, 1]
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = cam.invViewProjMatrix * ndc;
    return view.xyz / view.w;
}

vec3 Camera_reconstructWorldPos(const Camera cam, vec2 uv, float depth) {
    vec4 ndc  = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = cam.invViewProjMatrix * ndc;
    return world.xyz / world.w;
}

vec3 Camera_projectWorld(const Camera cam, vec3 worldPos) {
    vec4 clip = cam.viewProjMatrix * vec4(worldPos, 1.0);
    return clip.xyz / clip.w;
}

vec3 Camera_rayDirection(const Camera cam, vec2 uv) {
    vec4 target = cam.invViewProjMatrix * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    return normalize(target.xyz / target.w - cam.position);
}

vec2 Camera_worldToPixel(const Camera cam, vec3 worldPos) {
    vec3 ndc = Camera_projectWorld(cam, worldPos);
    vec2 uv  = ndc.xy * 0.5 + 0.5;
    return uv * cam.viewport;
}