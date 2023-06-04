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

public class SunCalc
{
	private static final double
		rad = PI / 180,
		e = rad * 23.4397; // obliquity of the Earth

	public static final long
		dayMs = 1000 * 60 * 60 * 24,
		J1970 = 2440588,
		J2000 = 2451545;

	// https://github.com/mourner/suncalc#sun-position
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

	// https://github.com/mourner/suncalc#moon-position
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
