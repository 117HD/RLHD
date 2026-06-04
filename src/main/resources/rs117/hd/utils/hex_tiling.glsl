#pragma once

#include <utils/misc.glsl>

#define HEX_UV_OFFSET_ONLY        0
#define HEX_UV_OFFSET_WITH_MIRROR 1
#define HEX_UV_OFFSET_WITH_ROTATE 2
#define HEX_UV_MODE HEX_UV_OFFSET_WITH_ROTATE

#define HEX_EPS 0.001
#define HEX_DOMINANT 0.95

struct HexData {
    vec3 weights;       // Barycentric weights for triangle interpolation
    vec2 uv[3];         // Perturbed UV coordinates for each vertex
    vec2 vertex[3];     // Hexagonal cell vertex positions
    bool enabled;       // Flag indicating if hex computation is valid
    vec2 dPdx;          // Partial derivative for gradient-aware sampling
    vec2 dPdy;          // Partial derivative for gradient-aware sampling
    int dominantIdx;    // Precomputed dominant vertex index
};

// Converts regular UV space into skewed hex space
const mat2 HEX_MATRIX = mat2(
    1.7320508, -1.0,
    0.0,        2.0
);

// Perturb UV coordinates for a hex vertex to create variation
vec2 makeUV(vec2 uv, vec2 vertexPos, int mode) {
    vec4 h = hash24(vertexPos);
    vec2 p = uv;

    if(mode == HEX_UV_OFFSET_WITH_ROTATE) {
         p -= 0.5;

        // Discreate Rotation
        float angle = h.x * 6.28318530718;
        float s = sin(angle);
        float c = cos(angle);
        p = vec2(
            c * p.x - s * p.y,
            s * p.x + c * p.y
        );

        p += 0.5;
    } else if(mode == HEX_UV_OFFSET_WITH_MIRROR) {
        // Flip along U/W
        vec2 flipMask = step(0.5, h.xy) * 2.0 - 1.0;
        p *= flipMask;
    }

    // Offset & Scale
    float scaleJitter = mix(0.85, 1.15, h.z);
    return p * scaleJitter + h.w;
}

HexData buildHexData(vec2 uv, vec3 fragPos, float scale, float blend, int mode) {
    HexData h;
    if (scale <= 0.0) {
        h.enabled = false;
        return h;
    }
    h.enabled = true;

    // Derivatives (shared across samples)
    h.dPdx = dFdx(fragPos.xz);
    h.dPdy = dFdy(fragPos.xz);

    vec2 skew = fragPos.xz * scale * HEX_MATRIX;
    vec2 base = floor(skew);
    vec2 f = fract(skew);

    vec3 temp = vec3(f, 0.0);
    temp.z = 1.0 - temp.x - temp.y;

    float s = step(0.0, -temp.z);
    float s2 = 2.0 * s - 1.0;
    temp *= s2;

    // Triangle vertices
    vec2 v1 = base + vec2(s, s);
    vec2 v2 = base + vec2(s, 1.0 - s);
    vec2 v3 = base + vec2(1.0 - s, s);

    h.vertex[0] = v1;
    h.vertex[1] = v2;
    h.vertex[2] = v3;

    // Barycentric weights
    vec3 w = vec3(-temp.z, s - temp.y, s - temp.x);
    w = max(w, 0.0);

    // Sharpen blend
    w = pow(w, vec3(7.0 * blend));

    // Normalize
    float invSum = 1.0 / (w.x + w.y + w.z);
    h.weights = w * invSum;

    // UVs
    h.uv[0] = makeUV(uv, v1, mode);
    h.uv[1] = makeUV(uv, v2, mode);
    h.uv[2] = makeUV(uv, v3, mode);

    float maxW = max(max(h.weights.x, h.weights.y), h.weights.z);
    if (maxW > HEX_DOMINANT) {
        // Determine which weight is largest
        h.dominantIdx = (h.weights.x > h.weights.y)
            ? (h.weights.x > h.weights.z ? 0 : 2)
            : (h.weights.y > h.weights.z ? 1 : 2);
    } else {
        h.dominantIdx = -1; // no dominant vertex
    }

    return h;
}

vec4 sampleHex(sampler2D tex, HexData h) {
    if (!h.enabled)
        return texture(tex, h.uv[0]);

    if (h.dominantIdx >= 0)
        return textureGrad(tex, h.uv[h.dominantIdx], h.dPdx, h.dPdy);

    vec4 c0 = textureGrad(tex, h.uv[0], h.dPdx, h.dPdy);
    vec4 c1 = textureGrad(tex, h.uv[1], h.dPdx, h.dPdy);
    vec4 c2 = textureGrad(tex, h.uv[2], h.dPdx, h.dPdy);

    return c0 * h.weights.x + c1 * h.weights.y + c2 * h.weights.z;
}

vec3 sampleHexRGB(sampler2D tex, HexData h) {
    return sampleHex(tex, h).rgb;
}

vec4 sampleHex(sampler2DArray tex, vec3 uvw, HexData h) {
    if (!h.enabled)
    return texture(tex, uvw);

    if (h.dominantIdx >= 0)
        return textureGrad(tex, vec3(h.uv[h.dominantIdx], uvw.z), h.dPdx, h.dPdy);

    vec4 c0 = textureGrad(tex, vec3(h.uv[0], uvw.z), h.dPdx, h.dPdy);
    vec4 c1 = textureGrad(tex, vec3(h.uv[1], uvw.z), h.dPdx, h.dPdy);
    vec4 c2 = textureGrad(tex, vec3(h.uv[2], uvw.z), h.dPdx, h.dPdy);

    return c0 * h.weights.x + c1 * h.weights.y + c2 * h.weights.z;
}

vec3 sampleHexRGB(sampler2DArray tex, vec3 uvw, HexData h) {
    return sampleHex(tex, uvw, h).rgb;
}

vec3 debugHex(HexData h) {
    if (!h.enabled) return vec3(0.0);

    vec3 W = h.weights;

    vec2 v1 = h.vertex[0];
    vec2 v2 = h.vertex[1];
    vec2 v3 = h.vertex[2];

    vec3 res = vec3(0.0);

    int i1 = int(v1.x - v1.y) % 3;
    if (i1 < 0) i1 += 3;

    int hi = (i1 < 2) ? (i1 + 1) : 0;
    int lo = (i1 > 0) ? (i1 - 1) : 2;

    int i2 = (v1.x < v3.x) ? lo : hi;
    int i3 = (v1.x < v3.x) ? hi : lo;

    res.x = (i3 == 0) ? W.z : ((i2 == 0) ? W.y : W.x);
    res.y = (i3 == 1) ? W.z : ((i2 == 1) ? W.y : W.x);
    res.z = (i3 == 2) ? W.z : ((i2 == 2) ? W.y : W.x);

    return res;
}