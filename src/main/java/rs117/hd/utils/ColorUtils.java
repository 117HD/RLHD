/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

package rs117.hd.utils;

public class ColorUtils {
	private static final float EPS = 1e-10f;

	/**
	 * Row-major transformation matrices for conversion between RGB and XYZ color spaces.
	 * Fairman, H. S., Brill, M. H., & Hemmendinger, H. (1997).
	 * How the CIE 1931 color-matching functions were derived from Wright-Guild data.
	 * Color Research & Application, 22(1), 11–23.
	 * doi:10.1002/(sici)1520-6378(199702)22:1<11::aid-col4>3.0.co;2-7
	 */
	private static final float[] RGB_TO_XYZ_MATRIX = {
		.49f, .31f, .2f,
		.1769f, .8124f, .0107f,
		.0f,    .0099f, .9901f
	};
	private static final float[] XYZ_TO_RGB_MATRIX = {
		2.36449f,    -.896553f,  -.467937f,
		-.514935f,   1.42633f,    .0886025f,
		 .00514883f, -.0142619f, 1.00911f
	};

	/**
	 * Approximate UV coordinates in the CIE 1960 UCS color space from a color temperature specified in degrees Kelvin.
	 * @param kelvin temperature in degrees Kelvin. Valid from 1000 to 15000.
	 * @see <a href="https://doi.org/10.1002/col.5080100109">
	 *     Krystek, M. (1985). An algorithm to calculate correlated colour temperature.
	 *     Color Research & Application, 10(1), 38–40. doi:10.1002/col.5080100109
	 * </a>
	 * @return UV coordinates in the UCS color space
	 */
	public static float[] colorTemperatureToLinearRgb(double kelvin) {
		// UV coordinates in CIE 1960 UCS color space
		double[] uv = new double[] {
			(0.860117757 + 1.54118254e-4 * kelvin + 1.28641212e-7 * kelvin * kelvin)
				/ (1 + 8.42420235e-4 * kelvin + 7.08145163e-7 * kelvin * kelvin),
			(0.317398726 + 4.22806245e-5 * kelvin + 4.20481691e-8 * kelvin * kelvin)
				/ (1 - 2.89741816e-5 * kelvin + 1.61456053e-7 * kelvin * kelvin)
		};

		// xy coordinates in CIES 1931 xyY space
		double divisor = 2 * uv[0] - 8 * uv[1] + 4;
		double[] xy = new double[] { 3 * uv[0] / divisor,  2 * uv[1] / divisor };

		// CIE XYZ space
		float Y = 1;
		float[] XYZ = { (float) (xy[0] * Y / xy[1]), Y, (float) ((1 - xy[0] - xy[1]) * Y / xy[1]) };

		return XYZtoRGB(XYZ);
	}

	/**
	 * Transform from CIE 1931 XYZ color space to linear RGB.
	 * @param XYZ coordinates
	 * @return linear RGB coordinates
	 */
	public static float[] XYZtoRGB(float[] XYZ) {
		float[] RGB = new float[3];
		mat3MulVec3(RGB, XYZ_TO_RGB_MATRIX, XYZ);
		return RGB;
	}

	/**
	 * Transform from linear RGB to CIE 1931 XYZ color space.
	 * @param RGB linear RGB color coordinates
	 * @return XYZ color coordinates
	 */
	public static float[] RGBtoXYZ(float[] RGB) {
		float[] XYZ = new float[3];
		mat3MulVec3(XYZ, RGB_TO_XYZ_MATRIX, RGB);
		return XYZ;
	}

	private static void mat3MulVec3(float[] out, float[] m, float[] v) {
		out[0] = m[0] * v[0] + m[1] * v[1] + m[2] * v[2];
		out[1] = m[3] * v[0] + m[4] * v[1] + m[5] * v[2];
		out[2] = m[6] * v[0] + m[7] * v[1] + m[8] * v[2];
	}

	// Conversion functions to and from sRGB and linear color space.
	// The implementation is based on the sRGB EOTF given in the Khronos Data Format Specification.
	// Source: https://web.archive.org/web/20220808015852/https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.pdf
	// Page number 130 (146 in the PDF)
	public static float linearToSrgb(float c) {
		return c <= 0.0031308 ?
			c * 12.92f :
			(float) (1.055 * Math.pow(c, 1 / 2.4) - 0.055);
	}

	public static float srgbToLinear(float c) {
		return c <= 0.04045f ?
			c / 12.92f :
			(float) Math.pow((c + 0.055) / 1.055, 2.4);
	}

	public static float[] linearToSrgb(float[] c) {
		float[] result = new float[c.length];
		for (int i = 0; i < c.length; i++)
			result[i] = linearToSrgb(c[i]);
		return result;
	}

	public static float[] srgbToLinear(float[] c) {
		float[] result = new float[c.length];
		for (int i = 0; i < c.length; i++)
			result[i] = srgbToLinear(c[i]);
		return result;
	}

	/**
	 * Float modulo that returns the answer with the same sign as the modulus.
	 */
	private static float mod(float x, float modulus) {
		return (float) (x - Math.floor(x / modulus) * modulus);
	}

	private static float clamp(float value, float min, float max) {
		return Math.min(Math.max(value, min), max);
	}

	private static int clamp(int value, int min, int max) {
		return Math.min(Math.max(value, min), max);
	}

	/**
	 * Convert sRGB in the range 0-1 from sRGB to HSL in the range 0-1.
	 *
	 * @param srgb float[3]
	 * @return hsl float[3]
	 * @link <a href="https://web.archive.org/web/20230619214343/https://en.wikipedia.org/wiki/HSL_and_HSV#Color_conversion_formulae">Wikipedia: HSL and HSV</a>
	 */
	public static float[] srgbToHsl(float[] srgb) {
		float V = Math.max(Math.max(srgb[0], srgb[1]), srgb[2]);
		float X_min = Math.min(Math.min(srgb[0], srgb[1]), srgb[2]);
		float C = V - X_min;

		float H = 0;
		if (C > 0) {
			if (V == srgb[0]) {
				H = mod((srgb[1] - srgb[2]) / C, 6);
			} else if (V == srgb[1]) {
				H = (srgb[2] - srgb[0]) / C + 2;
			} else {
				H = (srgb[0] - srgb[1]) / C + 4;
			}
			assert H >= 0 && H <= 6;
		}

		float L = (V + X_min) / 2;
		float divisor = 1 - Math.abs(2 * L - 1);
		float S_L = Math.abs(divisor) < EPS ? 0 : C / divisor;
		return new float[] { H / 6, S_L, L };
	}

	/**
	 * Convert HSL in the range 0-1 to sRGB in the range 0-1.
	 *
	 * @param hsl float[3]
	 * @return srgb float[3]
	 * @link <a href="https://web.archive.org/web/20230619214343/https://en.wikipedia.org/wiki/HSL_and_HSV#Color_conversion_formulae">Wikipedia: HSL and HSV</a>
	 */
	public static float[] hslToSrgb(float[] hsl) {
		float C = hsl[1] * (1 - Math.abs(2 * hsl[2] - 1));
		float H_prime = hsl[0] * 6;
		float m = hsl[2] - C / 2;

		float r = clamp(Math.abs(H_prime - 3) - 1, 0, 1) * C + m;
		float g = clamp(2 - Math.abs(H_prime - 2), 0, 1) * C + m;
		float b = clamp(2 - Math.abs(H_prime - 4), 0, 1) * C + m;
		return new float[] { r, g, b };
	}

	public static float[] hslToHsv(float[] hsl) {
		float v = hsl[2] + hsl[1] * Math.min(hsl[2], 1 - hsl[2]);
		return new float[] { hsl[0], Math.abs(v) < EPS ? 0 : 2 * (1 - hsl[2] / v), v };
	}

	public static float[] hsvToHsl(float[] hsv) {
		float l = hsv[2] * (1 - hsv[1] / 2);
		float divisor = Math.min(l, 1 - l);
		return new float[] { hsv[0], Math.abs(divisor) < EPS ? 0 : (hsv[2] - l) / divisor, l };
	}

	/**
	 * Convert sRGB in the range 0-1 from sRGB to HSV (also known as HSB) in the range 0-1.
	 *
	 * @param srgb float[3]
	 * @return hsv float[3]
	 * @link <a href="https://web.archive.org/web/20230619214343/https://en.wikipedia.org/wiki/HSL_and_HSV#Color_conversion_formulae">Wikipedia: HSL and HSV</a>
	 */
	public static float[] srgbToHsv(float[] srgb) {
		return hslToHsv(srgbToHsl(srgb));
	}

	/**
	 * Convert HSV (also known as HSB) in the range 0-1 to sRGB in the range 0-1.
	 *
	 * @param hsv float[3]
	 * @return srgb float[3]
	 * @link <a href="https://web.archive.org/web/20230619214343/https://en.wikipedia.org/wiki/HSL_and_HSV#Color_conversion_formulae">Wikipedia: HSL and HSV</a>
	 */
	public static float[] hsvToSrgb(float[] hsv) {
		return hslToSrgb(hsvToHsl(hsv));
	}

	/**
	 * Convert red, green and blue in the range 0-255 from sRGB to linear RGB in the range 0-1.
	 *
	 * @param r red color
	 * @param g green color
	 * @param b blue color
	 * @return float[3] linear rgb values from 0-1
	 */
	public static float[] rgb(float r, float g, float b) {
		return new float[] {
			srgbToLinear(r / 255f),
			srgbToLinear(g / 255f),
			srgbToLinear(b / 255f)
		};
	}

	public static int packHsl(float[] hsl) {
		int H = clamp(Math.round((hsl[0] - .0078125f) * (0x3F + 1)), 0, 0x3F);
		int S = clamp(Math.round((hsl[1] - .0625f) * (0x7 + 1)), 0, 0x7);
		int L = clamp(Math.round(hsl[2] * (0x7F + 1)), 0, 0x7F);
		return H << 10 | S << 7 | L;
	}

	public static float[] unpackHsl(int hsl) {
		// 6-bit hue | 3-bit saturation | 7-bit lightness
		float H = (hsl >> 10 & 0x3F) / (0x3F + 1f) + .0078125f;
		float S = (hsl >> 7 & 0x7) / (0x7 + 1f) + .0625f;
		float L = (hsl & 0x7F) / (0x7F + 1f);
		return new float[] { H, S, L };
	}

	public static int srgbToPackedHsl(float[] srgb) {
		return packHsl(srgbToHsl(srgb));
	}

	public static float[] packedHslToSrgb(int hsl) {
		return hslToSrgb(unpackHsl(hsl));
	}

	public static float[] unpackARGB(int argb) {
		return new float[] {
			(argb >> 16 & 0xFF) / (float) 0xFF,
			(argb >> 8 & 0xFF) / (float) 0xFF,
			(argb & 0xFF) / (float) 0xFF,
			(argb >> 24 & 0xFF) / (float) 0xFF
		};
	}
}
