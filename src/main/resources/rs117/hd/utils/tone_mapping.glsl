/* Source: https://www.shadertoy.com/view/fsXcz4
 *
 * Copyright(c) 2022 Björn Ottosson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this softwareand associated documentation files(the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and /or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions :
 * The above copyright noticeand this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
#pragma once

#include <utils/color_utils.glsl>

const float TONEMAP_SOFTNESS_SCALE = 0.2; // controls softness of RGB clipping
const float TONEMAP_OFFSET = 0.75; // controls how colors desaturate as they brighten. 0 results in that colors never fluoresce, 1 in very saturated colors
const float TONEMAP_CHROMA_SCALE = 1.2; // overall scale of chroma
const float TONEMAP_EPSILON = 1e-6;

// Origin: https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/
// Using this since it was easy to differentiate, same technique would work for any curve
vec3 s_curve(vec3 x) {
    float a = 2.51f;
    float b = 0.03f;
    float c = 2.43f;
    float d = 0.59f;
    float e = 0.14f;
    x = max(x, 0.0);
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// derivative of s-curve
vec3 d_s_curve(vec3 x) {
    float a = 2.51f;
    float b = 0.03f;
    float c = 2.43f;
    float d = 0.59f;
    float e = 0.14f;

    x = max(x, 0.0);
    vec3 r = (x * (c * x + d) + e);
    return (a * x * (d * x + 2.0 * e) + b * (e - c * x * x)) / (r * r);
}

vec2 findCenterAndPurity(vec3 x) {
    // Matrix derived for (c_smooth+s_smooth) to be an approximation of the macadam limit
    // this makes it some kind of g0-like estimate
    const mat3 M = mat3(
        2.26775149, -1.43293879,  0.1651873,
        -0.98535505,  2.1260072, -0.14065215,
        -0.02501605, -0.26349465,  1.2885107
    );

    x = x*M;

    float x_min = min(x.r, min(x.g, x.b));
    float x_max = max(x.r, max(x.g, x.b));

    float c = 0.5 * (x_max + x_min);
    float s = x_max - x_min;
    if (s < TONEMAP_EPSILON)
        return vec2(c, 0.0);

    // math trickery to create values close to c and s, but without producing hard edges
    vec3 y = (x - c) / s;
    float c_smooth = c + dot(y * y * y, vec3(1.0 / 3.0)) * s;
    float s_smooth = sqrt(dot(x - c_smooth, x - c_smooth) / 2.0);
    return vec2(c_smooth, s_smooth);
}

float calculateC(vec3 lms) {
    // Most of this could be precomputed
    // Creating a transform that maps R,G,B in the target gamut to have same distance from grey axis

    vec3 lmsR = linearSrgbToLmsCbrt(vec3(1.0, 0.0, 0.0));
    vec3 lmsG = linearSrgbToLmsCbrt(vec3(0.0, 1.0, 0.0));
    vec3 lmsB = linearSrgbToLmsCbrt(vec3(0.0, 0.0, 1.0));

    vec3 uDir = (lmsR - lmsG) / sqrt(2.0);
    vec3 vDir = (lmsR + lmsG - 2.0 * lmsB) / sqrt(6.0);

    mat3 to_uv = inverse(mat3(
        1.0, uDir.x, vDir.x,
        1.0, uDir.y, vDir.y,
        1.0, uDir.z, vDir.z
    ));

    vec3 _uv = lms * to_uv;

    return sqrt(_uv.y * _uv.y + _uv.z * _uv.z);
}

vec2 calculateMC(vec3 c) {
    vec3 lms = linearSrgbToLmsCbrt(c);
    float M = findCenterAndPurity(lms).x;
    return vec2(M, calculateC(lms));
}

vec2 expandShape(vec3 rgb, vec2 ST) {
    vec2 MC = calculateMC(rgb);
    vec2 STnew = vec2(MC.x / MC.y, (1.0 - MC.x) / MC.y);
    STnew = (STnew + 3.0 * STnew * STnew * MC.y);

    return vec2(min(ST.x, STnew.x), min(ST.y, STnew.y));
}

float expandScale(vec3 rgb, vec2 ST, float scale) {
    vec2 MC = calculateMC(rgb);
    float Cnew = 1.0 / (ST.x / MC.x + ST.y / (1.0 - MC.x));
    return max(MC.y / Cnew, scale);
}

vec2 approximateShape() {
    float m = -TONEMAP_SOFTNESS_SCALE * 0.2;
    float s = 1.0 + (TONEMAP_SOFTNESS_SCALE * 0.2 + TONEMAP_SOFTNESS_SCALE * 0.8);

    vec2 ST = vec2(1000.0, 1000.0);
    ST = expandShape(m + s * vec3(1.0, 0.0, 0.0), ST);
    ST = expandShape(m + s * vec3(1.0, 1.0, 0.0), ST);
    ST = expandShape(m + s * vec3(0.0, 1.0, 0.0), ST);
    ST = expandShape(m + s * vec3(0.0, 1.0, 1.0), ST);
    ST = expandShape(m + s * vec3(0.0, 0.0, 1.0), ST);
    ST = expandShape(m + s * vec3(1.0, 0.0, 1.0), ST);

    float scale = 0.0;
    scale = expandScale(m + s * vec3(1.0, 0.0, 0.0), ST, scale);
    scale = expandScale(m + s * vec3(1.0, 1.0, 0.0), ST, scale);
    scale = expandScale(m + s * vec3(0.0, 1.0, 0.0), ST, scale);
    scale = expandScale(m + s * vec3(0.0, 1.0, 1.0), ST, scale);
    scale = expandScale(m + s * vec3(0.0, 0.0, 1.0), ST, scale);
    scale = expandScale(m + s * vec3(1.0, 0.0, 1.0), ST, scale);

    return ST/scale;
}

vec3 tonemap_hue_preserving(vec3 c) {
    vec3 lms = linearSrgbToLmsCbrt(c);

    vec2 MP = findCenterAndPurity(lms);

    float I = (MP.x + (1.0 - TONEMAP_OFFSET) * MP.y);
    lms = lms * I * I;
    I = I * I * I;
    vec3 dLms = lms - I;

    float Icurve = s_curve(vec3(I)).x;
    if (Icurve <= 0.0)
        return vec3(0.0);
    // s_curve is flat after clipping, so there is no remaining chroma response.
    // Using the unclipped derivative here retained tinted highlights above white.
    if (Icurve >= 1.0)
        return vec3(1.0);
    lms = 1.0f + TONEMAP_CHROMA_SCALE * dLms * d_s_curve(vec3(I)) / Icurve;
    I = pow(Icurve, 1.0 / 3.0);

    lms = lms * I;

    // compress to a smooth approximation of the target gamut
    float M = findCenterAndPurity(lms).x;
    vec2 ST = approximateShape(); // this can be precomputed, only depends on RGB gamut
    float C = calculateC(lms);
    if (C > TONEMAP_EPSILON) {
        M = clamp(M, TONEMAP_EPSILON, 1.0 - TONEMAP_EPSILON);
        float C_smooth_gamut = 1.0 / (ST.x / M + ST.y / (1.0 - M));
        lms = (lms - M) / sqrt(C * C / (C_smooth_gamut * C_smooth_gamut) + 1.0) + M;
    }

    return lmsCbrtToLinearSrgb(lms);
}

// Best-effort inverse from display-linear RGB [0, 1] to scene-linear HDR.
// Does not invert softClipColor, sRGB encoding, or any other output adjustments.
// Exact apart from numerical tolerances where the forward mapping is unclipped
// and its reconstructed RGB is nonnegative. Unreachable colors are approximated.
vec3 inverse_tonemap_hue_preserving(vec3 color) {
    vec3 lms = linearSrgbToLmsCbrt(clamp(color, 0.0, 1.0));
    vec2 MP = findCenterAndPurity(lms);
    if (MP.x <= 0.0)
        return vec3(0.0);

    // Compression preserves the center and scales purity linearly. Invert
    // C_out = C_in / sqrt(1 + (C_in / gamutRadius)^2).
    float C = calculateC(lms);
    if (C > TONEMAP_EPSILON) {
        float M = clamp(MP.x, TONEMAP_EPSILON, 1.0 - TONEMAP_EPSILON);
        vec2 ST = approximateShape();
        float gamutRadius = 1.0 / (ST.x / M + ST.y / (1.0 - M));
        float ratio = C / gamutRadius;
        float expansion = inversesqrt(max(1.0 - ratio * ratio, TONEMAP_EPSILON));
        // Arbitrary display colors may lie outside the compressed gamut. Limit
        // expansion to an unclipped intensity instead of reconstructing unbounded HDR.
        float purity = (1.0 - TONEMAP_OFFSET) * MP.y;
        if (purity > 0.0)
            expansion = min(expansion, max(0.0, 1.0 - M) / purity);
        lms = M + (lms - M) * expansion;
    }

    // Center + weighted purity recovers the cube root of the mapped intensity:
    // the forward chroma residual has zero center + weighted purity.
    MP = findCenterAndPurity(lms);
    float mappedRoot = clamp(MP.x + (1.0 - TONEMAP_OFFSET) * MP.y, 0.0, 1.0);
    if (mappedRoot <= 0.0)
        return vec3(0.0);
    float mappedIntensity = mappedRoot * mappedRoot * mappedRoot;
    if (mappedIntensity <= 0.0)
        return vec3(0.0);

    // Invert s_curve's rational quadratic on its increasing, unclipped branch.
    // At white this chooses the first clipping point (about 7.24), not infinity.
    float a = 2.51 - 2.43 * mappedIntensity;
    float b = 0.03 - 0.59 * mappedIntensity;
    float c = 0.14 * mappedIntensity;
    float discriminant = sqrt(b * b + 4.0 * a * c);
    float intensity;
    if (b >= 0.0) {
        intensity = 2.0 * c / (discriminant + b);
    } else {
        intensity = (discriminant - b) / (2.0 * a);
    }

    // Clipped white has no recoverable chroma; choose its lowest neutral HDR preimage.
    if (mappedIntensity >= 1.0)
        return vec3(intensity);

    float root = pow(intensity, 1.0 / 3.0);
    float chromaScale = mappedRoot * mappedRoot /
        (TONEMAP_CHROMA_SCALE * d_s_curve(vec3(intensity)).x * root * root);
    lms = root + (lms - mappedRoot) * chromaScale;
    // Some assumed display colors have no nonnegative scene-linear preimage.
    return max(lmsCbrtToLinearSrgb(lms), vec3(0.0));
}

vec3 softSaturate(vec3 x, vec3 a) {
    a = clamp(a, 0.0, TONEMAP_SOFTNESS_SCALE);
    a = 1.0 + a;
    x = min(x, a);
    vec3 b = (a - 1.0) * sqrt(a / (2.0 - a));
    return 1.0 - (sqrt((x - a) * (x - a) + b * b) - b) / (sqrt(a * a + b * b) - b);
}

vec3 softClipColor(vec3 color) {
    // soft clip of rgb values to avoid artifacts of hard clipping
    // causes hues distortions, but is a smooth mapping
    // not quite sure this mapping is easy to invert, but should be possible to construct similar ones that do

    float grey = 0.2;

    vec3 x = color - grey;
    vec3 xsgn = sign(x);
    vec3 xscale = 0.5 + xsgn * (0.5 - grey);
    x /= xscale;

    float maxRGB = max(color.r, max(color.g, color.b));
    float minRGB = min(color.r, min(color.g, color.b));

    // A bright companion channel softens negative clipping, while a dark companion
    // channel does the same above 1. Keep both widths valid for fully out-of-gamut RGB.
    float softness_0 = clamp(maxRGB / (1.0 + TONEMAP_SOFTNESS_SCALE) * TONEMAP_SOFTNESS_SCALE, 0.0, TONEMAP_SOFTNESS_SCALE);
    float softness_1 = clamp((1.0 - minRGB) / (1.0 + TONEMAP_SOFTNESS_SCALE) * TONEMAP_SOFTNESS_SCALE, 0.0, TONEMAP_SOFTNESS_SCALE);

    vec3 softness = vec3(0.5) * (softness_0 + softness_1 + xsgn * (softness_1 - softness_0));

    return grey + xscale * xsgn * softSaturate(abs(x), softness);
}
