/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

package rs117.hd.utils;

public class ColorUtils
{
	/**
	 * Row-major transformation matrices for conversion between RGB and XYZ color spaces.
	 * Fairman, H. S., Brill, M. H., & Hemmendinger, H. (1997).
	 * How the CIE 1931 color-matching functions were derived from Wright-Guild data.
	 * Color Research & Application, 22(1), 11–23.
	 * doi:10.1002/(sici)1520-6378(199702)22:1<11::aid-col4>3.0.co;2-7
	 */
	private static final float[] RGB_TO_XYZ_MATRIX = {
		.49f,   .31f,   .2f,
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

	public static void mat3MulVec3(float[] out, float[] m, float[] v) {
		out[0] = m[0] * v[0] + m[1] * v[1] + m[2] * v[2];
		out[1] = m[3] * v[0] + m[4] * v[1] + m[5] * v[2];
		out[2] = m[6] * v[0] + m[7] * v[1] + m[8] * v[2];
	}
}
