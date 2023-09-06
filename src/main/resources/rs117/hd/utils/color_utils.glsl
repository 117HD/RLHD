/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
#pragma once

#include utils/constants.glsl

/**
 * Row-major transformation matrices for conversion between RGB and XYZ color spaces.
 *
 * Fairman, H. S., Brill, M. H., & Hemmendinger, H. (1997).
 * How the CIE 1931 color-matching functions were derived from Wright-Guild data.
 * Color Research & Application, 22(1), 11–23.
 * doi:10.1002/(sici)1520-6378(199702)22:1<11::aid-col4>3.0.co;2-7
 */
const mat3 RGB_TO_XYZ_MATRIX = transpose(mat3(
    .49,   .31,   .2,
    .1769, .8124, .0107,
    .0,    .0099, .9901
));
const mat3 XYZ_TO_RGB_MATRIX = transpose(mat3(
    2.36449,    -.896553,  -.467937,
    -.514935,   1.42633,    .0886025,
    0.00514883, -.0142619, 1.00911
));

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
    float H_prime = hsl[0] * 6;
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
