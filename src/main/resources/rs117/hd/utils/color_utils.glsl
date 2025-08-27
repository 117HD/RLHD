/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
#pragma once

#include <utils/constants.glsl>

/**
 * Column-major transformation matrices for conversion between RGB and XYZ color spaces.
 *
 * Fairman, H. S., Brill, M. H., & Hemmendinger, H. (1997).
 * How the CIE 1931 color-matching functions were derived from Wright-Guild data.
 * Color Research & Application, 22(1), 11–23.
 * doi:10.1002/(sici)1520-6378(199702)22:1<11::aid-col4>3.0.co;2-7
 */
const mat3 RGB_TO_XYZ_MATRIX = mat3(
    .49, .1769, .0,
    .31, .8124, .0099,
    .2,  .0107, .9901
);
const mat3 XYZ_TO_RGB_MATRIX = mat3(
    2.36449,  -.514935,  0.00514883,
    -.896553, 1.42633,   -.0142619,
    -.467937,  .0886025, 1.00911
);

/**
 * Transform from CIE 1931 XYZ color space to linear RGB.
 * @param XYZ coordinates
 * @return linear RGB coordinates
 */
vec3 XYZtoRGB(vec3 XYZ) {
    return XYZ_TO_RGB_MATRIX * XYZ;
}

/**
 * Transform from linear RGB to CIE 1931 XYZ color space.
 * @param RGB linear RGB color coordinates
 * @return XYZ color coordinates
 */
vec3 RGBtoXYZ(vec3 RGB) {
    return RGB_TO_XYZ_MATRIX * RGB;
}

/**
 * Approximate UV coordinates in the CIE 1960 UCS color space from a color temperature specified in degrees Kelvin.
 * @param kelvin temperature in degrees Kelvin. Valid from 1000 to 15000.
 * @see <a href="https://doi.org/10.1002/col.5080100109">
 *     Krystek, M. (1985). An algorithm to calculate correlated colour temperature.
 *     Color Research & Application, 10(1), 38–40. doi:10.1002/col.5080100109
 * </a>
 * @return UV coordinates in the UCS color space
 */
vec3 colorTemperatureToLinearRgb(float kelvin) {
    // UV coordinates in CIE 1960 UCS color space
    vec2 uv = vec2(
        (0.860117757 + 1.54118254e-4 * kelvin + 1.28641212e-7 * kelvin * kelvin)
            / (1 + 8.42420235e-4 * kelvin + 7.08145163e-7 * kelvin * kelvin),
        (0.317398726 + 4.22806245e-5 * kelvin + 4.20481691e-8 * kelvin * kelvin)
            / (1 - 2.89741816e-5 * kelvin + 1.61456053e-7 * kelvin * kelvin)
    );

    // xy coordinates in CIES 1931 xyY space
    vec2 xy = vec2(3 * uv.x, 2 * uv.y) / (2 * uv.x - 8 * uv.y + 4);

    // CIE XYZ space
    const float Y = 1;
    vec3 XYZ = Y * vec3(xy.x / xy.y, 1, (1 - xy.x - xy.y) / xy.y);

    vec3 linearRgb = XYZtoRGB(XYZ);
    float m = max(linearRgb.x, max(linearRgb.y, linearRgb.z));
    linearRgb = linearRgb / m;
    return linearRgb;
}

// Item 3.2 in https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.709-6-201506-I!!PDF-E.pdf
float luminance(vec3 rgb) {
    return dot(rgb, vec3(0.2126f, 0.7152f, 0.0722f));
}

vec3 look(vec3 c) {
    vec3 slope = vec3(1.0);
    vec3 power = vec3(1 + (COLOR_PICKER.r * 255 - 100) * .005);
    vec3 sat = vec3(1 + (COLOR_PICKER.g * 255 - 100) * .005);
    c = pow(c * slope, power);
    float luma = luminance(c);
    c = luma + sat * (c - luma);
    return c;
}

vec3 aces(vec3 x) {
  const float a = 2.51;
  const float b = 0.03;
  const float c = 2.43;
  const float d = 0.59;
  const float e = 0.14;
  return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

const mat3x3 ACESInputMat = mat3x3
(
    0.59719, 0.35458, 0.04823,
    0.07600, 0.90834, 0.01566,
    0.02840, 0.13383, 0.83777
);

// ODT_SAT => XYZ => D60_2_D65 => sRGB
const mat3x3 ACESOutputMat = mat3x3
(
     1.60475, -0.53108, -0.07367,
    -0.10208,  1.10813, -0.00605,
    -0.00327, -0.07276,  1.07602
);

vec3 RRTAndODTFit(vec3 v)
{
    vec3 a = v * (v + 0.0245786f) - 0.000090537f;
    vec3 b = v * (0.983729f * v + 0.4329510f) + 0.238081f;
    return a / b;
}

vec3 ACESFitted(vec3 color)
{
    color = color * ACESInputMat;

    // Apply RRT and ODT
    color = RRTAndODTFit(color);

    color = color * ACESOutputMat;

    return color;
}

vec3 ToneMap_Reinhard2(vec3 linCol)
{
    float white = 0.8;
    vec3 a = linCol * (1.0 + (linCol / (white * white)));
    vec3 b = 1.0 + linCol;
    return a / b;
}

vec3 OETF_REC709(vec3 linearColor)
{
    float a = 0.0031308;
	float b = 0.055;
	float c = 12.92;
	float m = 1.0 / 2.4;

	vec3 color = clamp(linearColor, 0.0, 1.0);
	color.x = color.x > a ? ((1.0 + b) * pow(color.x, m) - b) : (c * color.x);
	color.y = color.y > a ? ((1.0 + b) * pow(color.y, m) - b) : (c * color.y);
	color.z = color.z > a ? ((1.0 + b) * pow(color.z, m) - b) : (c * color.z);

	return color;
}

vec3 ToneMap_AgX(vec3 linCol, int lookMode)
{
    // Minimal AgX, see by https://iolite-engine.com/blog_posts/minimal_agx_implementation

    mat3 agx_mat = mat3(
        0.842479062253094, 0.0423282422610123, 0.0423756549057051,
        0.0784335999999992,  0.878468636469772,  0.0784336,
        0.0792237451477643, 0.0791661274605434, 0.879142973793104
    );

    mat3 agx_mat_inv = mat3(
        1.19687900512017, -0.0528968517574562, -0.0529716355144438,
        -0.0980208811401368, 1.15190312990417, -0.0980434501171241,
        -0.0990297440797205, -0.0989611768448433, 1.15107367264116
    );

    float min_ev = -12.47393;
    float max_ev = 4.026069;
    float bias = 1.0;

    // Input transform
    vec3 val = agx_mat * (linCol * bias);

    // Log2 space encoding
    val = clamp(log2(val), min_ev, max_ev);
    val = (val - min_ev) / (max_ev - min_ev);

    // Apply sigmoid function approximation
    val = ((((((((((15.5 * val) - 40.14) * val) + 31.96) * val) - 6.868) * val) + 0.4298) * val) + 0.1191) * val - 0.00232;

    // Apply Look Transform
    float luma = dot(val, vec3(0.2126, 0.7152, 0.0722));
    vec3 offset = vec3(0.0);
    vec3 slope = vec3(1.0);
    vec3 power = vec3(1.0);
    float sat = 1.0;

    if(lookMode == 1) // 117 HD
    {
        slope = vec3(1.0);
        power = vec3(2.35);
        sat = 1.0;
    }
    else if(lookMode == 2) // "Punchy"
    {
        slope = vec3(1.0);
        power = vec3(1.35);
        sat = 1.4;
    }

    val = pow(val * slope + offset, power);
    val = luma + sat * (val - luma);

    // Inverse Input transform
    return agx_mat_inv * val;
}

// Lottes 2016, "Advanced Techniques and Optimization of HDR Color Pipelines"
vec3 lottes(vec3 x) {
  const vec3 a = vec3(1.6);
  const vec3 d = vec3(0.977);
  const vec3 hdrMax = vec3(8.0);
  const vec3 midIn = vec3(0.18);
  const vec3 midOut = vec3(0.267);

  const vec3 b =
      (-pow(midIn, a) + pow(hdrMax, a) * midOut) /
      ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);
  const vec3 c =
      (pow(hdrMax, a * d) * pow(midIn, a) - pow(hdrMax, a) * pow(midIn, a * d) * midOut) /
      ((pow(hdrMax, a * d) - pow(midIn, a * d)) * midOut);

  return pow(x, a) / (pow(x, a * d) * b + c);
}

// Conversion functions to and from sRGB and linear color space.
// The implementation is based on the sRGB EOTF given in the Khronos Data Format Specification.
// Source: https://web.archive.org/web/20220808015852/https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.pdf
// Page number 130 (146 in the PDF)
vec3 srgbToLinear(vec3 srgb) {
  return mix(
    srgb / 12.92,
    pow((srgb + vec3(0.055)) / vec3(1.055), vec3(2.4)),
    step(vec3(0.04045), srgb));
}

vec3 linearToSrgb(vec3 rgb) {
  return mix(
    rgb * 12.92,
    1.055 * pow(rgb, vec3(1 / 2.4)) - 0.055,
    step(vec3(0.0031308), rgb));
}

vec4 srgbToLinear(vec4 srgb) {
    return vec4(srgbToLinear(srgb.rgb), srgb.a);
}

vec4 linearToSrgb(vec4 rgb) {
    return vec4(linearToSrgb(rgb.rgb), rgb.a);
}

float srgbToLinear(float srgb) {
  return mix(
    srgb / 12.92,
    pow((srgb + float(0.055)) / float(1.055), float(2.4)),
    step(float(0.04045), srgb));
}

float linearToSrgb(float rgb) {
  return mix(
    rgb * 12.92,
    1.055 * pow(rgb, 1 / 2.4) - 0.055,
    step(0.0031308, rgb));
}

// https://web.archive.org/web/20230619214343/https://en.wikipedia.org/wiki/HSL_and_HSV#Color_conversion_formulae
vec3 srgbToHsl(vec3 srgb) {
    float V = max(max(srgb.r, srgb.g), srgb.b);
    float X_min = min(min(srgb.r, srgb.g), srgb.b);
    float C = V - X_min;

    float H = 0;
    if (C > 0) {
        if (V == srgb.r) {
            H = mod((srgb.g - srgb.b) / C, 6);
        } else if (V == srgb.g) {
            H = (srgb.b - srgb.r) / C + 2;
        } else {
            H = (srgb.r - srgb.g) / C + 4;
        }
    }

    float L = (V + X_min) / 2;
    float divisor = 1 - abs(2 * L - 1);
    float S_L = abs(divisor) < EPS ? 0 : C / divisor;
    return vec3(H / 6, S_L, L);
}

vec3 hslToSrgb(vec3 hsl) {
    float C = (1 - abs(2 * hsl[2] - 1)) * hsl[1];
    float H_prime = fract(hsl[0]) * 6;
    float m = hsl[2] - C / 2;

    float r = clamp(abs(H_prime - 3) - 1, 0, 1);
    float g = clamp(2 - abs(H_prime - 2), 0, 1);
    float b = clamp(2 - abs(H_prime - 4), 0, 1);
    return vec3(r, g, b) * C + m;
}

vec3 hslToHsv(vec3 hsl) {
    float v = hsl[2] + hsl[1] * min(hsl[2], 1 - hsl[2]);
    return vec3(hsl[0], abs(v) < EPS ? 0 : 2 * (1 - hsl[2] / v), v);
}

vec3 hsvToHsl(vec3 hsv) {
    float l = hsv[2] * (1 - hsv[1] / 2);
    float divisor = min(l, 1 - l);
    return vec3(hsv[0], abs(divisor) < EPS ? 0 : (hsv[2] - l) / divisor, l);
}

vec3 srgbToHsv(vec3 rgb) {
    return hslToHsv(srgbToHsl(rgb));
}

vec3 hsvToSrgb(vec3 hsv) {
    return hslToSrgb(hsvToHsl(hsv));
}

// Pack HSL int Jagex format
int packHsl(vec3 hsl) {
    int H = clamp(int(round((hsl[0] - .0078125f) * 64)), 0, 63);
    int S = clamp(int(round((hsl[1] - .0625f) * 8)), 0, 7);
    int L = clamp(int(round(hsl[2] * 128)), 0, 127);
    return H << 10 | S << 7 | L;
}

// Unpack HSL from Jagex format
vec3 unpackHsl(int hsl) {
    // 6-bit hue | 3-bit saturation | 7-bit lightness
    float H = (hsl >> 10 & 63) / 64.f + .0078125f;
    float S = (hsl >> 7 & 7) / 8.f + .0625f;
    float L = (hsl & 127) / 128.f;
    return vec3(H, S, L);
}

int srgbToPackedHsl(vec3 srgb) {
    return packHsl(srgbToHsl(srgb));
}

vec3 packedHslToSrgb(int hsl) {
    return hslToSrgb(unpackHsl(hsl));
}
