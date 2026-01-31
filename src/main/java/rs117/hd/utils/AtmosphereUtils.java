/*
 * Based on https://github.com/mourner/suncalc:
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
import lombok.extern.slf4j.Slf4j;
import rs117.hd.scene.TimeOfDay;

import static java.lang.Math.PI;
import static java.lang.Math.acos;
import static java.lang.Math.asin;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.tan;
import static rs117.hd.utils.ColorUtils.rgb;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class AtmosphereUtils
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
	 *
	 * @param millis  time in milliseconds since the Unix epoch
	 * @param latLong latitude and longitude coordinates
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#sun-position">suncalc npm documentation</a>
	 */
	public static double[] getSunAngles(long millis, double[] latLong) {
		double
			phi = rad * latLong[0],
			lw = rad * -latLong[1],
			d = toDays(-millis); // Reverse time, since Gielinor orbits & spins in reverse compared to Earth

		double[] c = sunCoords(d);
		double H = siderealTime(d, lw) - c[1];

		double azimuth = azimuth(H, phi, c[0]);
		double altitude = altitude(H, phi, c[0]);
		return new double[] { azimuth, altitude };
	}

	public static float[] getDirectionalLight(long millis, double[] latLong) {
		double[] angles = getSunAngles(millis, latLong);

		// Use night illumination as a base
		float[] rgb = multiply(
			ColorUtils.colorTemperatureToLinearRgb(4100),
			(float) AtmosphereUtils.getMoonIllumination(millis)[0] * .2f
		);

		if (!TimeOfDay.isNight(angles)) {
			float[][] altitudeTemperatureRange = {
				{ 3, 2500 },
				{ 5, 2600 },
				{ 10, 3000 },
				{ 15, 3300 },
				{ 20, 3600 },
				{ 30, 4000 },
				{ 40, 4300 },
				{ 50, 4750 },
				{ 60, 5250 },
				{ 70, 5500 },
				{ 80, 5750 },
				{ 90, 6000 }
			};
			float temperature = interpolate((float) Math.toDegrees(angles[1]), altitudeTemperatureRange);
			float strength = (float) sin(angles[1]);
			strength *= strength;
			strength *= 3;
			float[] sunIllumination = ColorUtils.colorTemperatureToLinearRgb(temperature);
			sunIllumination = multiply(sunIllumination, strength);
			add(rgb, rgb, sunIllumination);
		}

		return rgb;
	}

	public static float[] getAmbientColor(long millis, double[] latLong) {
		// degrees above horizon 0-90, sRGB from 0-255
		Object[][] altitudeColorRange = {
			{ -5, new Color(113, 140, 180) },
			{ 25, new Color(192, 185, 255) },
			{ 40, new Color(185, 214, 255) },
		};

		double[] angles = getSunAngles(millis, latLong);
		return interpolateSrgb((float) Math.toDegrees(angles[1]), altitudeColorRange);
	}

	public static float[] getSkyColor(long millis, double[] latLong) {
		// degrees above horizon 0-90, sRGB from 0-255
		Object[][] altitudeColorRange = {
			{ -10, new Color(10, 10, 14) },
			{ 25, new Color(153, 153, 201) },
			{ 40, new Color(185, 214, 255) },
		};

		double[] angles = getSunAngles(millis, latLong);
		var rgb = interpolateSrgb((float) Math.toDegrees(angles[1]), altitudeColorRange);
		return ColorUtils.linearToSrgb(rgb);
	}

	public static float interpolate(float x, float[][] keyframesDegreesValue) {
		int end = keyframesDegreesValue.length - 1;
		int i = 0;
		while (i < end && x < keyframesDegreesValue[i][0])
			i++;

		if (i == end)
			return keyframesDegreesValue[end][1];

		var from = keyframesDegreesValue[i];
		var to = keyframesDegreesValue[i + 1];
		var x1 = from[0];
		var x2 = to[0];

		float t = clamp((x - x1) / (x2 - x1), 0, 1);
		return mix(from[1], to[1], t);
	}

	/**
	 * Converts colors in range from sRGB to linear RGB, then interpolates, and returns the interpolated linear RGB color.
	 *
	 * @param x                    interpolation value
	 * @param keyframesDegreesSrgb values
	 * @return interpolated linear RGB
	 */
	public static float[] interpolateSrgb(float x, Object[][] keyframesDegreesSrgb) {
		int end = keyframesDegreesSrgb.length - 1;
		int i = 0;
		while (i < end && x > ((Number) keyframesDegreesSrgb[i + 1][0]).floatValue())
			i++;

		if (i == end)
			return rgb((Color) keyframesDegreesSrgb[end][1]);

		var from = keyframesDegreesSrgb[i];
		var to = keyframesDegreesSrgb[i + 1];
		var x1 = ((Number) from[0]).floatValue();
		var x2 = ((Number) to[0]).floatValue();

		float t = clamp((x - x1) / (x2 - x1), 0, 1);
		return mix(rgb((Color) from[1]), rgb((Color) to[1]), t);
	}

	/**
	 * Calculate angles for the moon's position in the sky at a given time and location.
	 *
	 * @param millis  time in milliseconds since the Unix epoch
	 * @param latLong latitude and longitude coordinates
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#moon-position">suncalc npm documentation</a>
	 */
	public static double[] getMoonPosition(long millis, double[] latLong) {
		double
			phi = rad * latLong[0],
			lw = rad * -latLong[1],
			d = toDays(-millis); // Reverse time, since Gielinor orbits & spins in reverse compared to Earth

		double[] c = moonCoords(d);
		double
			dec = c[0],
			ra = c[1],
			dist = c[2],
			H = siderealTime(d, lw) - ra,
			h = altitude(H, phi, dec),
			// formula 14.1 of "Astronomical Algorithms" 2nd edition by Jean Meeus (Willmann-Bell, Richmond) 1998.
			pa = atan2(sin(H), tan(phi) * cos(dec) - sin(dec) * cos(H));

		double azimuth = azimuth(H, phi, dec);
		double altitude = h + astroRefraction(h); // altitude correction for refraction
		return new double[] {
			azimuth,
			altitude,
			dist,
			pa
		};
	}

	public static double[] getMoonIllumination(long millis) {
		double d = toDays(-millis);
		return getMoonIllumination(sunCoords(d), moonCoords(d));
	}

	// https://github.com/mourner/suncalc#moon-illumination
	public static double[] getMoonIllumination(double[] sunCoords, double[] moonCoords) {
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

	public static double toJulian(long millis) {
		return (double) millis / (double) dayMs - .5 + J1970;
	}

	public static double toDays(long millis) {
		return toJulian(millis) - J2000;
	}

	private static double[] sunCoords(double julianDay) {
		double
			M = solarMeanAnomaly(julianDay),
			L = eclipticLongitude(M);

		return new double[] {
			declination(L, 0),
			rightAscension(L, 0)
		};
	}

	private static double[] moonCoords(double julianDay) { // geocentric ecliptic coordinates of the moon
		double
			L = rad * (218.316 + 13.176396 * julianDay), // ecliptic longitude
			M = rad * (134.963 + 13.064993 * julianDay), // mean anomaly
			F = rad * (93.272 + 13.229350 * julianDay),  // mean distance

			l = L + rad * 6.289 * sin(M), // longitude
			b = rad * 5.128 * sin(F),     // latitude
			dt = 385001 - 20905 * cos(M);  // distance to the moon in km

		return new double[] {
			declination(l, b),
			rightAscension(l, b),
			dt
		};
	}

	private static double solarMeanAnomaly(double julianDay) {
		return rad * (357.5291 + 0.98560028 * julianDay);
	}

	private static double eclipticLongitude(double M) {
		double
			C = rad * (1.9148 * sin(M) + 0.02 * sin(2 * M) + 0.0003 * sin(3 * M)), // equation of center
			P = rad * 102.9372; // perihelion of the Earth

		return M + C + P + PI;
	}

	private static double declination(double l, double b) {
		return asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l));
	}

	private static double rightAscension(double l, double b) {
		return atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l));
	}

	private static double siderealTime(double d, double lw) {
		return rad * (280.16 + 360.9856235 * d) - lw;
	}

	private static double azimuth(double H, double phi, double dec) {
		return atan2(sin(H), cos(H) * sin(phi) - tan(dec) * cos(phi));
	}

	private static double altitude(double H, double phi, double dec) {
		return asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(H));
	}

	private static double astroRefraction(double h) {
		if (h < 0) // the following formula works for positive altitudes only.
		{
			h = 0; // if h = -0.08901179 a div/0 would occur.
		}

		// formula 16.4 of "Astronomical Algorithms" 2nd edition by Jean Meeus (Willmann-Bell, Richmond) 1998.
		// 1.02 / tan(h + 10.26 / (h + 5.10)) h in degrees, result in arc minutes -> converted to rad:
		return 0.0002967 / Math.tan(h + 0.00312536 / (h + 0.08901179));
	}
}
