/*
 * Ported from https://github.com/mourner/suncalc
 *
 * Copyright (c) 2014, Vladimir Agafonkin
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.utils;

import java.awt.Color;
import static java.lang.Math.PI;
import static java.lang.Math.acos;
import static java.lang.Math.asin;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.tan;

public class SunCalc
{
	private static final double
		rad = PI / 180,
		e = rad * 23.4397; // obliquity of the Earth

	public static final long
		dayMs = 1000 * 60 * 60 * 24,
		J1970 = 2440588,
		J2000 = 2451545;

	/**
	 * Calculate angles for the sun's position in the sky at a given time and location.
	 * @param millis time in milliseconds since the Unix epoch
	 * @param latitude coordinate
	 * @param longitude coordinate
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#sun-position">suncalc npm documentation</a>
	 */
	public static double[] getPosition(long millis, double latitude, double longitude)
	{
		double
			lw = rad * -longitude,
			phi = rad * latitude,
			d = toDays(millis);

		double[] c = sunCoords(d);
		double H = siderealTime(d, lw) - c[1];

		return new double[]{azimuth(H, phi, c[0]), altitude(H, phi, c[0])};
	}

	public static float getStrength(double[] angles) {
		double altitude = angles[1];

		if (altitude < 0) {
			return 0;
		}

		// Calculate the sunlight strength as a normalized value from 0 to 1
		float strength = (float) (altitude / (Math.PI / 2.0));

		return strength;
	}

	public static float[] getColor(long millis, double[] latLong) {
		double[] position = getPosition(millis, latLong[0], latLong[1]);
		double azimuth = position[0];
		double altitudeDegrees = Math.toDegrees(position[1]);

		double[][] altitudeTemperatureRange = {
				{0, 15000},
				{5, 2000},
				{15, 3000},
				{20, 4000},
				{30, 5000},
				{45, 5500},
				{60, 6000},
				{75, 6500},
				{80, 6500}
		};

		double temperature = interpolate(altitudeDegrees, altitudeTemperatureRange);

		Color color = getRGBFromK((int) temperature);

		float red = color.getRed() / 255f;
		float green = color.getGreen() / 255f;
		float blue = color.getBlue() / 255f;

		return new float[]{red, green, blue};
	}

	public static double interpolate(double x, double[][] range) {
		int n = range.length;
		int i = 0;
		while (i < n && x > range[i][0]) {
			i++;
		}
		if (i == 0) {
			return range[0][1];
		} else if (i == n) {
			return range[n - 1][1];
		} else {
			double x1 = range[i - 1][0];
			double x2 = range[i][0];
			double y1 = range[i - 1][1];
			double y2 = range[i][1];
			return y1 + (y2 - y1) * (x - x1) / (x2 - x1);
		}
	}

	/**
	 * Add attribute for stasikos https://gist.github.com/stasikos/06b02d18f570fc1eaa9f
	 */
	public static Color getRGBFromK(int temperature) {
		// Used this: https://gist.github.com/paulkaplan/5184275 at the beginning
		// based on http://stackoverflow.com/questions/7229895/display-temperature-as-a-color-with-c
		// this answer: http://stackoverflow.com/a/24856307
		// (so, just interpretation of pseudocode in Java)

		double x = temperature / 1000.0;
		if (x > 40) {
			x = 40;
		}
		double red;
		double green;
		double blue;

		// R
		if (temperature < 6527) {
			red = 1;
		} else {
			double[] redpoly = {4.93596077e0, -1.29917429e0,
					1.64810386e-01, -1.16449912e-02,
					4.86540872e-04, -1.19453511e-05,
					1.59255189e-07, -8.89357601e-10};
			red = poly(redpoly, x);

		}
		// G
		if (temperature < 850) {
			green = 0;
		} else if (temperature <= 6600) {
			double[] greenpoly = {-4.95931720e-01, 1.08442658e0,
					-9.17444217e-01, 4.94501179e-01,
					-1.48487675e-01, 2.49910386e-02,
					-2.21528530e-03, 8.06118266e-05};
			green = poly(greenpoly, x);
		} else {
			double[] greenpoly = {3.06119745e0, -6.76337896e-01,
					8.28276286e-02, -5.72828699e-03,
					2.35931130e-04, -5.73391101e-06,
					7.58711054e-08, -4.21266737e-10};

			green = poly(greenpoly, x);
		}
		// B
		if (temperature < 1900) {
			blue = 0;
		} else if (temperature < 6600) {
			double[] bluepoly = {4.93997706e-01, -8.59349314e-01,
					5.45514949e-01, -1.81694167e-01,
					4.16704799e-02, -6.01602324e-03,
					4.80731598e-04, -1.61366693e-05};
			blue = poly(bluepoly, x);
		} else {
			blue = 1;
		}

		red = clamp(red, 0, 1);
		blue = clamp(blue, 0, 1);
		green = clamp(green, 0, 1);
		return new Color((float) red, (float) green, (float) blue);
	}
	public static double poly(double[] coefficients, double x) {
		double result = coefficients[0];
		double xn = x;
		for (int i = 1; i < coefficients.length; i++) {
			result += xn * coefficients[i];
			xn *= x;

		}
		return result;
	}
	public static double clamp(double x, double min, double max) {
		if (x < min) {
			return min;
		}
		if (x > max) {
			return max;
		}
		return x;
	}
	// end attribute

	/**
	 * Calculate angles for the moon's position in the sky at a given time and location.
	 * @param millis time in milliseconds since the Unix epoch
	 * @param latitude coordinate
	 * @param longitude coordinate
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#moon-position">suncalc npm documentation</a>
	 */
	public static double[] getMoonPosition(long millis, double latitude, double longitude)
	{
		double
			lw = rad * -longitude,
			phi = rad * latitude,
			d = toDays(millis);

		double[] c = moonCoords(d);
		double
			dec = c[0],
			ra = c[1],
			dist = c[2],
			H = siderealTime(d, lw) - ra,
			h = altitude(H, phi, dec),
			// formula 14.1 of "Astronomical Algorithms" 2nd edition by Jean Meeus (Willmann-Bell, Richmond) 1998.
			pa = atan2(sin(H), tan(phi) * cos(dec) - sin(dec) * cos(H));

		h = h + astroRefraction(h); // altitude correction for refraction

		return new double[]{
			azimuth(H, phi, dec),
			h,
			dist,
			pa
		};
	}

	public static double[] getMoonIllumination(long millis)
	{
		return getMoonIllumination(millis, sunCoords(millis), moonCoords(millis));
	}

	// https://github.com/mourner/suncalc#moon-illumination
	public static double[] getMoonIllumination(long millis, double[] sunCoords, double[] moonCoords)
	{
		double
			sdist = 149598000, // distance from Earth to Sun in km
			sdec = sunCoords[0],
			mdec = moonCoords[0],
			sra = sunCoords[1],
			mra = moonCoords[1],
			mdist = moonCoords[2],

			phi = acos(sin(sdec) * sin(mdec) + cos(sdec) * cos(mdec) * cos(sra - mra)),
			inc = atan2(sdist * sin(phi), mdist - sdist * cos(phi)),
			angle = atan2(cos(sdec) * sin(sra - mra), sin(sdec) * cos(mdec) - cos(sdec) * sin(mdec) * cos(sra - mra));

		return new double[]{
			(1 + cos(inc)) / 2, // fraction
			0.5 + 0.5 * inc * (angle < 0 ? -1 : 1) / Math.PI, // phase
			angle
		};
	}

	public static float getMoonStrength(double[] angles) {
		double azimuth = angles[0];
		double altitude = angles[1];

		if (altitude < 0) {
			return 0;
		}

		// Calculate the Moon light strength as a normalized value from 0 to 1
		float strength = (float) (altitude / (Math.PI / 2.0));

		return strength;
	}

	public static double toJulian(long millis)
	{
		return (double) millis / (double) dayMs - .5 + J1970;
	}

	public static double toDays(long millis)
	{
		return toJulian(millis) - J2000;
	}

	private static double[] sunCoords(double d)
	{
		double
			M = solarMeanAnomaly(d),
			L = eclipticLongitude(M);

		return new double[]{
			declination(L, 0),
			rightAscension(L, 0)
		};
	}

	private static double[] moonCoords(double d)
	{ // geocentric ecliptic coordinates of the moon
		double
			L = rad * (218.316 + 13.176396 * d), // ecliptic longitude
			M = rad * (134.963 + 13.064993 * d), // mean anomaly
			F = rad * (93.272 + 13.229350 * d),  // mean distance

			l = L + rad * 6.289 * sin(M), // longitude
			b = rad * 5.128 * sin(F),     // latitude
			dt = 385001 - 20905 * cos(M);  // distance to the moon in km

		return new double[]{
			declination(l, b),
			rightAscension(l, b),
			dt
		};
	}

	private static double solarMeanAnomaly(double d)
	{
		return rad * (357.5291 + 0.98560028 * d);
	}

	private static double eclipticLongitude(double M)
	{
		double
			C = rad * (1.9148 * sin(M) + 0.02 * sin(2 * M) + 0.0003 * sin(3 * M)), // equation of center
			P = rad * 102.9372; // perihelion of the Earth

		return M + C + P + PI;
	}

	private static double declination(double l, double b)
	{
		return asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l));
	}

	private static double rightAscension(double l, double b)
	{
		return atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l));
	}

	private static double siderealTime(double d, double lw)
	{
		return rad * (280.16 + 360.9856235 * d) - lw;
	}

	private static double azimuth(double H, double phi, double dec)
	{
		return atan2(sin(H), cos(H) * sin(phi) - tan(dec) * cos(phi));
	}

	private static double altitude(double H, double phi, double dec)
	{
		return asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(H));
	}

	private static double astroRefraction(double h)
	{
		if (h < 0) // the following formula works for positive altitudes only.
		{
			h = 0; // if h = -0.08901179 a div/0 would occur.
		}

		// formula 16.4 of "Astronomical Algorithms" 2nd edition by Jean Meeus (Willmann-Bell, Richmond) 1998.
		// 1.02 / tan(h + 10.26 / (h + 5.10)) h in degrees, result in arc minutes -> converted to rad:
		return 0.0002967 / Math.tan(h + 0.00312536 / (h + 0.08901179));
	}
}
