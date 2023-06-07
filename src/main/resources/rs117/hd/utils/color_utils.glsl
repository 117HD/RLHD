/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

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
