package rs117.hd.scene;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.runelite.http.api.worlds.WorldRegion;
import rs117.hd.utils.SunCalc;
import rs117.hd.utils.Vector;

import static rs117.hd.utils.ColorUtils.rgb;

public enum TimeOfDay
{
	DAY,
	NIGHT,
	;

	public static final float MINUTES_PER_DAY = 30 / 60.f;

	/**
	 * Get the current sun or moon angles for a given set of coordinates and simulated day length in minutes.
	 *
	 * @param latLong   array of latitude and longitude coordinates
	 * @param dayLength in minutes per day
	 * @return the azimuth and altitude angles in radians
	 * @see <a href="https://en.wikipedia.org/wiki/Horizontal_coordinate_system">Horizontal coordinate system</a>
	 */
	public static double[] getShadowAngles(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] angles = SunCalc.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		return isNight(angles) ?
			SunCalc.getMoonPosition(modifiedDate.toEpochMilli(), latLong) :
			angles;
	}

	public static double[] getSunAngles(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getSunAngles(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getLightColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getDirectionalLight(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getAmbientColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getAmbientColor(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getSkyColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return SunCalc.getSkyColor(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getNightAmbientColor() {
		return Vector.multiply(rgb(56, 99, 161), 2);
	}

	public static float[] getNightLightColor() {
		return Vector.multiply(rgb(181, 205, 255), 0.25f);
	}

	public static Instant getModifiedDate(float dayLength) {
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
			case UNITED_KINGDOM:
			default:
				// Cambridge
				latitude = 52.1951;
				longitude = .1313;
				break;
		}
		return new double[] { latitude, longitude };
	}

	public static boolean isNight(double[] angles) {
		double angleFromZenith = Math.abs(angles[1] - Math.PI / 2);
		return angleFromZenith > Math.PI / 2;
	}
}
