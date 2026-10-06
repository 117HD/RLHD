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

import static java.lang.Math.PI;
import static java.lang.Math.acos;
import static java.lang.Math.asin;
import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.tan;

public final class AstronomyUtils {
	private static final double
		rad = PI / 180,
		e = rad * 23.4397; // obliquity of the Earth

	private static final long
		dayMs = 1000 * 60 * 60 * 24,
		J1970 = 2440588,
		J2000 = 2451545;

	/**
	 * Calculate angles for the sun's position in the sky at a given time and location.
	 *
	 * @param out     output altitude and azimuth angles in radians
	 * @param millis  time in milliseconds since the Unix epoch
	 * @param latLong latitude and longitude coordinates
	 * @return {@code out}. Altitude includes atmospheric refraction. Azimuth is clockwise from north.
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#sun-position">suncalc npm documentation</a>
	 */
	public static float[] getSunAngles(float[] out, long millis, float[] latLong) {
		double
			phi = rad * latLong[0],
			lw = rad * -latLong[1],
			d = toDays(millis), // Real (non-reversed) time so season, phase, and E/W match the real sky
			M = solarMeanAnomaly(d),
			L = eclipticLongitude(M),
			dec = declination(L, 0),
			ra = rightAscension(L, 0),
			H = siderealTime(d, lw) - ra;

		double h = altitude(H, phi, dec);
		out[0] = (float) (h + astroRefraction(h));
		out[1] = (float) (azimuth(H, phi, dec) + PI);
		return out;
	}

	/**
	 * Return the apparent solar altitude in radians.
	 */
	public static float getSunAltitude(long millis, float[] latLong) {
		double
			phi = rad * latLong[0],
			lw = rad * -latLong[1],
			d = toDays(millis),
			M = solarMeanAnomaly(d),
			L = eclipticLongitude(M),
			dec = declination(L, 0),
			ra = rightAscension(L, 0),
			H = siderealTime(d, lw) - ra;
		double h = altitude(H, phi, dec);
		return (float) (h + astroRefraction(h));
	}

	/**
	 * Calculate angles for the moon's position in the sky at a given time and location.
	 *
	 * @param out     output altitude, azimuth, and optionally distance and parallactic angle
	 * @param millis  time in milliseconds since the Unix epoch
	 * @param latLong latitude and longitude coordinates
	 * @return {@code out}. Angles are in radians. Altitude includes atmospheric refraction. Azimuth is clockwise from north.
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 * @see <a href="https://github.com/mourner/suncalc#moon-position">suncalc npm documentation</a>
	 */
	public static float[] getMoonPosition(float[] out, long millis, float[] latLong) {
		double
			phi = rad * latLong[0],
			lw = rad * -latLong[1],
			d = toDays(millis), // Real (non-reversed) time so season, phase, and E/W match the real sky
			L = rad * (218.316 + 13.176396 * d),
			M = rad * (134.963 + 13.064993 * d),
			F = rad * (93.272 + 13.229350 * d),
			l = L + rad * 6.289 * sin(M),
			b = rad * 5.128 * sin(F),
			dec = declination(l, b),
			ra = rightAscension(l, b),
			H = siderealTime(d, lw) - ra,
			h = altitude(H, phi, dec),
			// formula 14.1 of "Astronomical Algorithms" 2nd edition by Jean Meeus (Willmann-Bell, Richmond) 1998.
			pa = atan2(sin(H), tan(phi) * cos(dec) - sin(dec) * cos(H));

		out[0] = (float) (h + astroRefraction(h)); // altitude correction for refraction
		out[1] = (float) (azimuth(H, phi, dec) + PI);
		if (out.length > 2)
			out[2] = (float) (385001 - 20905 * cos(M)); // distance to the moon in km
		if (out.length > 3)
			out[3] = (float) pa;
		return out;
	}

	public static float[] getMoonIllumination(float[] out, long millis) {
		double d = toDays(millis); // Real (non-reversed) time so the phase matches the real-world moon
		double sunM = solarMeanAnomaly(d);
		double sunL = eclipticLongitude(sunM);
		double moonL = rad * (218.316 + 13.176396 * d);
		double moonM = rad * (134.963 + 13.064993 * d);
		double moonF = rad * (93.272 + 13.229350 * d);
		double l = moonL + rad * 6.289 * sin(moonM);
		double b = rad * 5.128 * sin(moonF);
		return getMoonIllumination(
			out,
			declination(sunL, 0), rightAscension(sunL, 0),
			declination(l, b), rightAscension(l, b),
			385001 - 20905 * cos(moonM)
		);
	}

	// https://github.com/mourner/suncalc#moon-illumination
	public static float[] getMoonIllumination(float[] out, double[] sunCoords, double[] moonCoords) {
		return getMoonIllumination(out, sunCoords[0], sunCoords[1], moonCoords[0], moonCoords[1], moonCoords[2]);
	}

	private static float[] getMoonIllumination(float[] out, double sdec, double sra, double mdec, double mra, double mdist) {
		double
			sdist = 149598000, // distance from Earth to Sun in km
			phi = acos(sin(sdec) * sin(mdec) + cos(sdec) * cos(mdec) * cos(sra - mra)),
			inc = atan2(sdist * sin(phi), mdist - sdist * cos(phi)),
			angle = atan2(cos(sdec) * sin(sra - mra), sin(sdec) * cos(mdec) - cos(sdec) * sin(mdec) * cos(sra - mra));

		out[0] = (float) ((1 + cos(inc)) / 2); // fraction
		out[1] = (float) (0.5 + 0.5 * inc * (angle < 0 ? -1 : 1) / Math.PI); // phase
		out[2] = (float) angle;
		return out;
	}

	private static double toJulian(long millis) {
		return (double) millis / (double) dayMs - .5 + J1970;
	}

	private static double toDays(long millis) {
		return toJulian(millis) - J2000;
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
		// Extend through the slightly negative geometric altitudes at which the
		// refracted Sun/Moon can still be visible. Below -1 degree, hold the
		// correction fixed: the disk has set and extrapolating approaches a pole.
		h = Math.max(h, -rad);

		// formula 16.4 of "Astronomical Algorithms" 2nd edition by Jean Meeus (Willmann-Bell, Richmond) 1998.
		// 1.02 / tan(h + 10.26 / (h + 5.10)) h in degrees, result in arc minutes -> converted to rad:
		return 0.0002967 / Math.tan(h + 0.00312536 / (h + 0.08901179));
	}
}
