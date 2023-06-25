package rs117.hd.scene;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.runelite.http.api.worlds.WorldRegion;
import rs117.hd.utils.SunCalc;

import static rs117.hd.utils.ColorUtils.rgb;

public enum TimeOfDay
{
	DAY,
	NIGHT,
	;

	private static final long MS_PER_MINUTE = 60000;
	private static final float NIGHT_RANGE = 2f;

	public static TimeOfDay getTimeOfDay(double[] latLong, int dayLength)
	{
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong);
		return isNight(angles) ? TimeOfDay.NIGHT : TimeOfDay.DAY;
	}

	/**
	 * Get the current sun or moon angles for a given set of coordinates and simulated day length in minutes.
	 * @param latLong array of latitude and longitude coordinates
	 * @param dayLength in minutes per day
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 */
	public static double[] getCurrentAngles(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong);
		return isNight(angles) ?
			SunCalc.getMoonPosition(modifiedDate.toEpochMilli(), latLong) :
			angles;
	}

	public static float getLightStrength(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] angles = SunCalc.getPosition(modifiedDate.toEpochMilli(), latLong);
		double[] moonAngles = SunCalc.getMoonPosition(modifiedDate.toEpochMilli(), latLong);
		return isNight(angles) ?
			SunCalc.getMoonStrength(moonAngles, angles, 10, 10) :
			SunCalc.getStrength(angles, 10);
	}

	public static float[] getLightColor(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getColor(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getAmbientColor(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getAmbientColor(modifiedDate.toEpochMilli(), latLong);
	}

	public static float getAmbientStrength(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getAmbientStrength(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getFogColor(double[] latLong, int dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getSkyColor(modifiedDate.toEpochMilli(), latLong);
	}

	//blend water color
	//blend ambient strength
	//introduce moon light and ambient cap to replace previous env settings

	public static float[] getNightFogColor() { return rgb(5, 5, 11); }
	public static float[] getNightAmbientColor() { return rgb(56, 99, 161); }
	public static float[] getNightLightColor() { return rgb(181, 205, 255); }
	public static float[] getNightWaterColor() { return rgb(8, 8, 18); }
	public static float getNightLightStrength() { return 0.25f; }
	public static float getNightAmbientStrength() { return 2f; }

	public static Instant getModifiedDate(int dayLength) {
		long currentTimeMillis = System.currentTimeMillis();
		Instant currentInstant = Instant.ofEpochMilli(currentTimeMillis);

		Duration elapsedDuration = Duration.between(
				currentInstant.truncatedTo(ChronoUnit.DAYS),
				currentInstant
		);
		double speedFactor = 24.0 * 60.0 / dayLength; // Speed factor to adjust the speed of time
		Duration modifiedDuration = elapsedDuration.multipliedBy((long) speedFactor);

		return currentInstant.plus(modifiedDuration);
	}

	public static double[] getLatLong(WorldRegion currentRegion) {
		double latitude;
		double longitude;
		switch (currentRegion) {
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
				longitude = .1313;
				break;
		}
		return new double[]{latitude, longitude};
	}

	private static boolean isNight(double[] angles) {
		double angleFromZenith = Math.abs(angles[1] - Math.PI / 2);
		return angleFromZenith > Math.PI / NIGHT_RANGE;
	}
}
