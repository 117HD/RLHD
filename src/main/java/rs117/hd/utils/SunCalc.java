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

import static java.lang.Math.PI;
import static java.lang.Math.acos;
import static java.lang.Math.asin;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.tan;
import static rs117.hd.utils.HDUtils.srgbToLinear;

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
	 * @param millis    time in milliseconds since the Unix epoch
	 * @param latLong   latitude and longitude coordinates
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#sun-position">suncalc npm documentation</a>
	 */
	public static double[] getPosition(long millis, double[] latLong)
	{
		double
			phi = rad * latLong[0],
			lw = rad * -latLong[1],
			d = toDays(-millis); // Reverse time, since Gielinor orbits & spins in reverse compared to Earth

		double[] c = sunCoords(d);
		double H = siderealTime(d, lw) - c[1];

		double azimuth = azimuth(H, phi, c[0]);
		double altitude = altitude(H, phi, c[0]);
		return new double[]{ azimuth, altitude };
	}

	/**
	 * Calculate strength of the sun's light based on the current altitude at a given time and location.
	 * @param angles     azimuth and altitude angles in radians
	 * @param startAngle  the angle over which to fade the light at the start and end of the day
	 * @return light strength float between 0 and 1
	 */
	public static float getStrength(double[] angles, double startAngle) {
		double altitude = angles[1];
		double angleFromZenith = Math.abs(altitude - Math.PI / 2);

		if (angleFromZenith > Math.PI / 2) { // Below horizon
			return 0;
		}

		float normalizedAltitude = (float) (altitude / (Math.PI / 2));
		float fadeOffset = (float) Math.toRadians(startAngle) / (float) (Math.PI / 2);
		float fadeStart = fadeOffset;
		float fadeEnd = 1 - fadeOffset;

		float fadedStrength = 0;
		if (normalizedAltitude >= fadeStart && normalizedAltitude <= fadeEnd) {
			fadedStrength = (float) Math.sin((normalizedAltitude - fadeStart) * (Math.PI / 2) / (fadeEnd - fadeStart));
		}
		return fadedStrength;
	}

	public static float[] getColor(long millis, double[] latLong)
	{
		double[] angles = getPosition(millis, latLong);
		float altitudeDegrees = (float) Math.toDegrees(angles[1]);
		if (altitudeDegrees > 90)
			altitudeDegrees = 90 - altitudeDegrees;

		float[][] altitudeTemperatureRange = {
			{-90, 13000 },
			{  0, 13000 },
			{  3, 2500 },
			{  5, 2600 },
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

		float temperature = interpolate(altitudeDegrees, altitudeTemperatureRange);
		return ColorUtils.colorTemperatureToLinearRgb(temperature);
	}

	public static float[] getAmbientColor(long millis, double[] latLong)
	{
		double[] position = getPosition(millis, latLong);
		float altitudeDegrees = (float) Math.toDegrees(position[1]);
		if (altitudeDegrees > 90)
			altitudeDegrees = 90 - altitudeDegrees;

		float[][] altitudeColorRange = {
			{-90,  56,  99, 161 },
			{  0,  56,  99, 161 },
			{  2, 147,  56, 161 }, // dawn/dusk blue
			{  4, 255, 114,  54 },
			{  7, 255, 178,  84 }, // sunrise/sunset orange
			{ 20, 173, 243, 255 },
			{ 30, 151, 186, 255 },
			{ 80, 151, 186, 255 }
		};

		float[] interpolatedColor = interpolateRGB(altitudeDegrees, altitudeColorRange);
		for (int i = 0; i < 3; i++)
			interpolatedColor[i] /= 255;
		return srgbToLinear(interpolatedColor);
	}

	public static float getAmbientStrength(long millis, double[] latLong)
	{
		double[] position = getPosition(millis, latLong);
		float altitudeDegrees = (float) Math.toDegrees(position[1]);
		if (altitudeDegrees > 90)
			altitudeDegrees = 90 - altitudeDegrees;

		float[][] altitudeStrengthRange = {
				{-90,  2 },
				{  0,  2 },
				{  3, 1 },
				{ 20, 1 },
				{ 30, 1 },
				{ 80, 1 }
		};

		return interpolate(altitudeDegrees, altitudeStrengthRange);
	}

	public static float[] getSkyColor(long millis, double[] latLong)
	{
		double[] position = getPosition(millis, latLong);
		float altitudeDegrees = (float) Math.toDegrees(position[1]);
		if (altitudeDegrees > 90)
			altitudeDegrees = 90 - altitudeDegrees;

		float[][] altitudeColorRange = {
				{-90,   5,   5, 11 },
				{  0,   5,   5, 11 },
				{  2,  14,  15, 33 },  // dawn/dusk blue
				{  4, 114,  48, 117 },
				{  7, 222, 170, 106 }, // sunrise/sunset orange
				{ 20, 185, 199, 255 },
				{ 30, 185, 214, 255 },
				{ 80, 185, 214, 255 }
		};

		float[] interpolatedColor = interpolateRGB(altitudeDegrees, altitudeColorRange);
		for (int i = 0; i < 3; i++)
			interpolatedColor[i] /= 255;
		return srgbToLinear(interpolatedColor);
	}

	public static float interpolate(float x, float[][] range)
	{
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
			float x1 = range[i - 1][0];
			float x2 = range[i][0];
			float y1 = range[i - 1][1];
			float y2 = range[i][1];
			return y1 + (y2 - y1) * (x - x1) / (x2 - x1);
		}
	}

	public static float[] interpolateRGB(float x, float[][] range)
	{
		int n = range.length;
		int i = 0;
		while (i < n && x > range[i][0]) {
			i++;
		}
		if (i == 0) {
			return range[0];
		} else if (i == n) {
			return range[n - 1];
		} else {
			float x1 = range[i - 1][0];
			float x2 = range[i][0];
			float[] y1 = range[i - 1];
			float[] y2 = range[i];
			float[] interpolatedColor = new float[3];
			for (int j = 1; j < 4; j++)
				interpolatedColor[j - 1] = y1[j] + (y2[j] - y1[j]) * (x - x1) / (x2 - x1);
			return interpolatedColor;
		}
	}

	/**
	 * Calculate angles for the moon's position in the sky at a given time and location.
	 *
	 * @param millis    time in milliseconds since the Unix epoch
	 * @param latLong   latitude and longitude coordinates
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#moon-position">suncalc npm documentation</a>
	 */
	public static double[] getMoonPosition(long millis, double[] latLong)
	{
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
		return new double[]{
			azimuth,
			altitude,
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

	public static float getMoonStrength(double[] moonAngles, double[] angles, double startAngle, double filterAngle) {
		double moonAltitude = moonAngles[1];
		double moonAngleFromZenith = Math.abs(moonAltitude - Math.PI / 2);

		if (moonAngleFromZenith > Math.PI / 2)
			return 0;

		// get moon strength
		float normalizedMoonAltitude = (float) (moonAltitude / (Math.PI / 2));

		// offset
		double fadeOffset = Math.toRadians(startAngle) / (Math.PI / 2);
		float fadeStart = (float) fadeOffset;
		float fadeEnd = 1 - (float) fadeOffset;

		float moonStrength = 0;
		if (normalizedMoonAltitude >= fadeStart && normalizedMoonAltitude <= fadeEnd) {
			moonStrength = (normalizedMoonAltitude - fadeStart) / (fadeEnd - fadeStart);
		}

		// filter based on sun altitude
		double sunAltitude = angles[1];
		double startFadeFactor = Math.max(0, (-sunAltitude - Math.toRadians(filterAngle)) / Math.toRadians(filterAngle));
		moonStrength *= startFadeFactor;

		if (sunAltitude > Math.toRadians(-filterAngle)) {
			double endFadeFactor = Math.max(0, (sunAltitude - Math.toRadians(-filterAngle)) / Math.toRadians(filterAngle));
			moonStrength *= (1 - endFadeFactor);
		}

		return moonStrength;
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
