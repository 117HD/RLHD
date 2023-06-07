package rs117.hd.scene;

import java.awt.Color;
import java.time.Instant;
import net.runelite.http.api.worlds.WorldRegion;
import rs117.hd.utils.SunCalc;

import static rs117.hd.utils.HDUtils.srgbToLinear;

public enum TimeOfDay
{
	DAY,
	NIGHT,
	;

	private static final long MS_PER_MINUTE = 60000;
	private static final float NIGHT_RANGE = 2f;

	public static TimeOfDay getTimeOfDay(double[] latLong, int dayLength)
	{
		Instant modifiedDate = getModifiedDate(Instant.now(), dayLength);

		double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
		angles[1] = Math.PI - angles[1]; // Flip the direction of rotation, since Gielinor rotates opposite to Earth

		if (isNight(angles[1]))
		{
			return TimeOfDay.NIGHT;
		}
		else
		{
			return TimeOfDay.DAY;
		}
	}

	/**
	 * Get the current sun or moon angles for a given set of coordinates and simulated day length in minutes.
	 * @param latLong array of latitude and longitude coordinates
	 * @param dayLength in minutes per day
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 */
	public static double[] getCurrentAngles(double[] latLong, int dayLength)
	{
		Instant modifiedDate = getModifiedDate(Instant.now(), dayLength);

		double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
		angles[1] = Math.PI - angles[1]; // Flip the direction of rotation, since Gielinor rotates opposite to Earth

		if (isNight(angles[1]))
		{
			// Switch to moon angles
			angles = SunCalc.getMoonPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
			angles[1] = Math.PI - angles[1]; // Flip the direction of rotation, since the moon orbits opposite to Earth's moon
		}

		return angles;
	}

	public static float getLightStrength(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(Instant.now(), dayLength);

		double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);
		double[] moonAngles = SunCalc.getMoonPosition(modifiedDate.toEpochMilli(), latLong[0], latLong[1]);

		float strength;

		if (isNight(angles[1]))
			strength = SunCalc.getMoonStrength(moonAngles, angles);
		else
			strength = SunCalc.getStrength(angles, 0.3f);

		return strength;
	}

	public static float[] getLightColor(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(Instant.now(), dayLength);

		return SunCalc.getColor(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getAmbientColor(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(Instant.now(), dayLength);

		return SunCalc.getAmbientColor(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getNightFogColor() { return rgbToLinear(5, 5, 11); }
	public static float[] getNightAmbientColor() { return rgbToLinear(56, 99, 161); }
	public static float[] getNightLightColor() { return rgbToLinear(181, 205, 255); }
	public static float[] getNightWaterColor() { return rgbToLinear(8, 8, 18); }
	public static float getNightLightStrength() { return 0.25f; }
	public static float getNightAmbientStrength() { return 2f; }


	public static Instant getModifiedDate(Instant currentDate, int dayLength)
	{
		long millisPerDay = dayLength * MS_PER_MINUTE;
		long elapsedMillis = currentDate.toEpochMilli() % millisPerDay;
		double speedFactor = 1440.0 / dayLength; // Speed factor to adjust the speed of time
		return currentDate.plusMillis((long) (elapsedMillis * speedFactor));
	}

	public static double[] getLatLong(WorldRegion currentRegion)
	{
		double latitude;
		double longitude;
		switch (currentRegion)
		{
			case AUSTRALIA:
				// Sydney
				latitude = -33.8478035;
				longitude = 150.6016588;
				break;
			case GERMANY:
				// Berlin
				latitude = 52.5063843;
				longitude = 13.0944281;
				break;
			case UNITED_STATES_OF_AMERICA:
				// Chicago
				latitude = 41.8337329;
				longitude = -87.7319639;
				break;
			default:
			case UNITED_KINGDOM:
				// Cambridge
				latitude = 52.1951;
				longitude = 0.1313;
				break;
		}
		return new double[]{latitude, longitude};
	}

	private static boolean isNight(double altitude) {
		double angleFromZenith = Math.abs(altitude - Math.PI / 2);
		return angleFromZenith > Math.PI / NIGHT_RANGE;
	}

	public static float[] rgbToLinear(int r, int g, int b) {
		Color color = new Color(r, g, b);
		float[] linearColor = new float[3];
		linearColor[0] = srgbToLinear(color.getRed() / 255f);
		linearColor[1] = srgbToLinear(color.getGreen() / 255f);
		linearColor[2] = srgbToLinear(color.getBlue() / 255f);
		return linearColor;
	}
}
