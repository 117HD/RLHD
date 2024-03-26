/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

package rs117.hd.utils;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.awt.Color;
import java.io.IOException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;

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

	public static float[] linearToSrgb(float... c) {
		float[] result = new float[c.length];
		for (int i = 0; i < c.length; i++)
			result[i] = linearToSrgb(c[i]);
		return result;
	}

	public static float[] srgbToLinear(float... c) {
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
	 * Convert sRGB in the range 0-1 to HSL in the range 0-1.
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

	/**
	 * Convert HSL in the range 0-1 to HSV in the range 0-1.
	 *
	 * @param hsl float[3]
	 * @return hsv float[3]
	 */
	public static float[] hslToHsv(float[] hsl) {
		float v = hsl[2] + hsl[1] * Math.min(hsl[2], 1 - hsl[2]);
		return new float[] { hsl[0], Math.abs(v) < EPS ? 0 : 2 * (1 - hsl[2] / v), v };
	}

	/**
	 * Convert HSV in the range 0-1 to HSL in the range 0-1.
	 *
	 * @param hsv float[3]
	 * @return hsl float[3]
	 */
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

	// Convenience functions for converting different formats into linear RGB, sRGB or packed HSL

	/**
	 * Convert red, green and blue in the range 0-255 from sRGB to linear RGB in the range 0-1.
	 *
	 * @param r red color
	 * @param g green color
	 * @param b blue color
	 * @return float[3] linear rgb values from 0-1
	 */
	public static float[] rgb(float r, float g, float b) {
		return srgbToLinear(srgb(r, g, b));
	}

	/**
	 * Convert hex color from sRGB to linear RGB in the range 0-1.
	 *
	 * @param hex RGB hex color
	 * @return float[3] linear rgb values from 0-1
	 */
	public static float[] rgb(String hex) {
		return srgbToLinear(srgb(hex));
	}

	/**
	 * Convert sRGB color packed as an int to linear RGB in the range 0-1.
	 *
	 * @param srgb packed sRGB
	 * @return float[3] linear rgb values from 0-1
	 */
	public static float[] rgb(int srgb) {
		return srgbToLinear(srgb(srgb));
	}

	/**
	 * Convert red, green and blue in the range 0-255 from sRGB to sRGB in the range 0-1.
	 *
	 * @param r red color
	 * @param g green color
	 * @param b blue color
	 * @return float[3] non-linear sRGB values from 0-1
	 */
	public static float[] srgb(float r, float g, float b) {
		return new float[] { r / 255f, g / 255f, b / 255f };
	}

	/**
	 * Convert hex color from sRGB to sRGB in the range 0-1.
	 *
	 * @param hex RGB hex color
	 * @return float[3] non-linear sRGB values from 0-1
	 */
	public static float[] srgb(String hex) {
		Color color = Color.decode(hex);
		return srgb(color.getRed(), color.getGreen(), color.getBlue());
	}

	/**
	 * Convert sRGB color packed as an int to sRGB in the range 0-1.
	 *
	 * @param srgb packed sRGB
	 * @return float[3] non-linear sRGB values from 0-1
	 */
	public static float[] srgb(int srgb) {
		return new float[] {
			(srgb >> 16 & 0xFF) / (float) 0xFF,
			(srgb >> 8 & 0xFF) / (float) 0xFF,
			(srgb & 0xFF) / (float) 0xFF,
		};
	}

	/**
	 * Convert alpha and sRGB color packed in an int as ARGB to sRGB in the range 0-1.
	 *
	 * @param alphaSrgb packed sRGB with a preceding alpha channel
	 * @return float[4] non-linear sRGB and alpha in the range 0-1
	 */
	public static float[] srgba(int alphaSrgb) {
		return new float[] {
			(alphaSrgb >> 16 & 0xFF) / (float) 0xFF,
			(alphaSrgb >> 8 & 0xFF) / (float) 0xFF,
			(alphaSrgb & 0xFF) / (float) 0xFF,
			(alphaSrgb >> 24 & 0xFF) / (float) 0xFF
		};
	}

	/**
	 * Convert red, green and blue in the range 0-255 from sRGB to packed HSL.
	 *
	 * @param r red color
	 * @param g green color
	 * @param b blue color
	 * @return int packed HSL
	 */
	public static int hsl(float r, float g, float b) {
		return srgbToPackedHsl(srgb(r, g, b));
	}

	/**
	 * Convert hex color from sRGB to packed HSL.
	 *
	 * @param rgbHex RGB hex color
	 * @return int packed HSL
	 */
	public static int hsl(String rgbHex) {
		return srgbToPackedHsl(srgb(rgbHex));
	}

	/**
	 * Convert sRGB color packed as an int to packed HSL.
	 *
	 * @param packedSrgb RGB hex color
	 * @return int packed HSL
	 */
	public static int hsl(int packedSrgb) {
		return srgbToPackedHsl(srgb(packedSrgb));
	}

	// Integer packing and unpacking functions

	public static int packRawRgb(int... rgb) {
		return rgb[0] << 16 | rgb[1] << 8 | rgb[2];
	}

	public static int packSrgb(float[] srgb) {
		int[] ints = new int[3];
		for (int i = 0; i < 3; i++)
			ints[i] = clamp(Math.round(srgb[i] * 0xFF), 0, 0xFF);
		return packRawRgb(ints);
	}

	public static int packRawHsl(int... hsl) {
		return hsl[0] << 10 | hsl[1] << 7 | hsl[2];
	}

	public static void unpackRawHsl(int[] out, int hsl) {
		// 6-bit hue | 3-bit saturation | 7-bit lightness
		out[0] = hsl >>> 10 & 0x3F;
		out[1] = hsl >>> 7 & 0x7;
		out[2] = hsl & 0x7F;
	}

	public static int[] unpackRawHsl(int hsl) {
		int[] out = new int[3];
		unpackRawHsl(out, hsl);
		return out;
	}

	public static int packHsl(float... hsl) {
		int H = clamp(Math.round((hsl[0] - .0078125f) * (0x3F + 1)), 0, 0x3F);
		int S = clamp(Math.round((hsl[1] - .0625f) * (0x7 + 1)), 0, 0x7);
		int L = clamp(Math.round(hsl[2] * (0x7F + 1)), 0, 0x7F);
		return packRawHsl(H, S, L);
	}

	public static float[] unpackHsl(int hsl) {
		// 6-bit hue | 3-bit saturation | 7-bit lightness
		float H = (hsl >>> 10 & 0x3F) / (0x3F + 1f) + .0078125f;
		float S = (hsl >>> 7 & 0x7) / (0x7 + 1f) + .0625f;
		float L = (hsl & 0x7F) / (0x7F + 1f);
		return new float[] { H, S, L };
	}

	public static int srgbToPackedHsl(float[] srgb) {
		return packHsl(srgbToHsl(srgb));
	}

	public static float[] packedHslToSrgb(int packedHsl) {
		return hslToSrgb(unpackHsl(packedHsl));
	}

	public static int linearRgbToPackedHsl(float[] linearRgb) {
		return srgbToPackedHsl(linearToSrgb(linearRgb));
	}

	public static float[] packedHslToLinearRgb(int hsl) {
		return srgbToLinear(packedHslToSrgb(hsl));
	}

	public static String srgbToHex(float... srgb) {
		return String.format("#%h", packSrgb(srgb));
	}

	public static String rgbToHex(float... linearRgb) {
		return srgbToHex(linearToSrgb(linearRgb));
	}

	@Slf4j
	public static class SrgbAdapter extends TypeAdapter<float[]> {
		private final float[] rgba = { 0, 0, 0, 1 };
		private final int[] rgbaInt = { 0, 0, 0, 255 };

		@Override
		public float[] read(JsonReader in) throws IOException {
			var token = in.peek();
			if (token == JsonToken.STRING)
				return ColorUtils.srgb(in.nextString());

			if (token != JsonToken.BEGIN_ARRAY)
				throw new IOException("Expected hex color code or array of color channels at " + GsonUtils.location(in));

			in.beginArray();

			int i = 0;
			while (in.hasNext() && in.peek() != JsonToken.END_ARRAY) {
				if (in.peek() == JsonToken.NULL) {
					log.warn("Skipping null value in color array at {}", GsonUtils.location(in));
					in.skipValue();
					continue;
				}

				if (in.peek() == JsonToken.NUMBER) {
					if (i > 3) {
						log.warn("Skipping extra elements in color array at {}", GsonUtils.location(in));
						break;
					}

					rgba[i++] = (float) in.nextDouble();
					continue;
				}

				throw new IOException("Unexpected type in color array: " + in.peek() + " at " + GsonUtils.location(in));
			}
			in.endArray();

			if (i < 3)
				throw new IOException("Too few elements in color array: " + i + " at " + GsonUtils.location(in));

			for (int j = 0; j < i; j++)
				rgba[j] /= 255;

			if (i == 4)
				return rgba;

			float[] rgb = new float[3];
			System.arraycopy(rgba, 0, rgb, 0, 3);
			return rgb;
		}

		@Override
		public void write(JsonWriter out, float[] src) throws IOException {
			if (src == null || src.length == 0) {
				out.nullValue();
				return;
			}

			if (src.length != 3 && src.length != 4)
				throw new IOException("The number of components must be 3 or 4 in a color array. Got " + Arrays.toString(src));

			for (int i = 0; i < src.length; i++)
				rgba[i] = src[i] * 255;

			// See if it can fit in a hex color code
			boolean canfit = true;
			for (int i = 0; i < src.length; i++) {
				float f = rgba[i];
				rgbaInt[i] = Math.round(f);
				if (Math.abs(f - rgbaInt[i]) > EPS) {
					canfit = false;
					break;
				}
			}

			if (canfit) {
				// Serialize it as a hex color code
				if (src.length == 3) {
					out.value(String.format("#%02x%02x%02x", rgbaInt[0], rgbaInt[1], rgbaInt[2]));
				} else {
					out.value(String.format("#%02x%02x%02x%02x", rgbaInt[0], rgbaInt[1], rgbaInt[2], rgbaInt[3]));
				}
			} else {
				out.beginArray();
				for (int i = 0; i < src.length; i++) {
					out.value(rgba[i]);
				}
				out.endArray();
			}
		}
	}

	@Slf4j
	public static class SrgbToLinearAdapter extends SrgbAdapter {
		@Override
		public float[] read(JsonReader in) throws IOException {
			return srgbToLinear(super.read(in));
		}

		@Override
		public void write(JsonWriter out, float[] src) throws IOException {
			super.write(out, linearToSrgb(src));
		}
	}
}
