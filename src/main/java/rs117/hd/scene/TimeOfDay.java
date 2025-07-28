package rs117.hd.scene;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import net.runelite.http.api.worlds.WorldRegion;
import rs117.hd.utils.AtmosphereUtils;
import rs117.hd.utils.Vector;

import static rs117.hd.utils.ColorUtils.rgb;

public class TimeOfDay
{
	public static final float MINUTES_PER_DAY = 30 / 60.f;
	
	// Static variables to maintain cycle state across config changes
	private static float lastDayLength = MINUTES_PER_DAY;
	private static long lastUpdateTime = 0;
	private static double accumulatedCycleTime = 0.0;

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
		double[] angles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		return isNight(angles) ?
			AtmosphereUtils.getMoonPosition(modifiedDate.toEpochMilli(), latLong) :
			angles;
	}

	public static double[] getSunAngles(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getLightColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return AtmosphereUtils.getDirectionalLight(modifiedDate.toEpochMilli(), latLong);
	}
	
	public static float[] getRegionalDirectionalLight(double[] latLong, float dayLength, float[] regionalDirectionalColor) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);
		
		// Get the dynamic directional light color
		float[] dynamicLight = AtmosphereUtils.getDirectionalLight(modifiedDate.toEpochMilli(), latLong);
		
		// Calculate blend factor - same as skybox and ambient for consistency
		float blendFactor;
		if (sunAltitudeDegrees >= 30) {
			// High sun - use pure regional color (100% regional) to match disabled behavior
			blendFactor = 1.0f;
		} else if (sunAltitudeDegrees >= 15) {
			// Medium sun - strong regional influence (75-100% regional)
			blendFactor = (float) (0.75 + ((sunAltitudeDegrees - 15) / 15.0) * 0.25);
		} else if (sunAltitudeDegrees >= 5) {
			// Sunset/late sunrise - moderate regional influence (50-75% regional)
			blendFactor = (float) (0.50 + ((sunAltitudeDegrees - 5) / 10.0) * 0.25);
		} else if (sunAltitudeDegrees >= 0) {
			// Low sun - moderate regional influence (30-50% regional)
			blendFactor = (float) (0.30 + (sunAltitudeDegrees / 5.0) * 0.20);
		} else {
			// Night/twilight - minimal regional influence (0-30% regional)
			blendFactor = (float) Math.max(0.0, 0.30 + sunAltitudeDegrees / 10.0 * 0.30);
		}
		
		// Regional color is already in linear space from environment manager
		// Blend the colors in linear space
		float[] blended = new float[3];
		for (int i = 0; i < 3; i++) {
			blended[i] = dynamicLight[i] * (1 - blendFactor) + regionalDirectionalColor[i] * blendFactor;
		}
		
		return blended;
	}

	public static float[] getAmbientColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return AtmosphereUtils.getAmbientColor(modifiedDate.toEpochMilli(), latLong);
	}
	
	public static float[] getRegionalAmbientLight(double[] latLong, float dayLength, float[] regionalAmbientColor) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);
		
		// Get the dynamic ambient light color
		float[] dynamicAmbient = AtmosphereUtils.getAmbientColor(modifiedDate.toEpochMilli(), latLong);
		
		// Calculate blend factor based on sun altitude - same as skybox for consistency
		float blendFactor;
		if (sunAltitudeDegrees >= 30) {
			// High sun - use pure regional color (100% regional) to match disabled behavior
			blendFactor = 1.0f;
		} else if (sunAltitudeDegrees >= 15) {
			// Medium sun - strong regional influence (75-100% regional)
			blendFactor = (float) (0.75 + ((sunAltitudeDegrees - 15) / 15.0) * 0.25);
		} else if (sunAltitudeDegrees >= 5) {
			// Sunset/late sunrise - moderate regional influence (50-75% regional)
			blendFactor = (float) (0.50 + ((sunAltitudeDegrees - 5) / 10.0) * 0.25);
		} else if (sunAltitudeDegrees >= 0) {
			// Low sun - moderate regional influence (30-50% regional)
			blendFactor = (float) (0.30 + (sunAltitudeDegrees / 5.0) * 0.20);
		} else {
			// Night/twilight - minimal regional influence (0-30% regional)
			blendFactor = (float) Math.max(0.0, 0.30 + sunAltitudeDegrees / 10.0 * 0.30);
		}
		
		// Blend the colors in linear space
		float[] blended = new float[3];
		for (int i = 0; i < 3; i++) {
			blended[i] = dynamicAmbient[i] * (1 - blendFactor) + regionalAmbientColor[i] * blendFactor;
		}
		
		return blended;
	}

	public static float[] getSkyColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return AtmosphereUtils.getSkyColor(modifiedDate.toEpochMilli(), latLong);
	}


	public static float[] getEnhancedSkyColor(double[] latLong, float dayLength, float[] regionalFogColor) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		
		// Convert sun altitude to degrees (-90 to +90)
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);
		
		// Enhanced sky color palette with gradual sunset to night transition
		Object[][] skyColorKeyframes = {
			// Deep night (sun well below horizon) - lightened and gradual progression
			{ -30.0, new java.awt.Color(35, 42, 58) },    // Deepest night (lightened)
			{ -15.0, new java.awt.Color(35, 42, 58) },    // Stable deep night (lightened)
			{ -8.0,  new java.awt.Color(42, 30, 80) },    // Early twilight brightening (lightened)
			{ 0.0,   new java.awt.Color(210, 135, 95) },  // Peak sunset red
			{ 8.0,   new java.awt.Color(220, 170, 115) }, // Late golden hour (dimmed)
			{ 12.0,  new java.awt.Color(220, 180, 145) }, // Warm late afternoon
			{ 15.0,  new java.awt.Color(200, 175, 160) }, // Soft warm light
			{ 18.0,  new java.awt.Color(180, 170, 175) }, // Afternoon transition
			{ 22.0,  new java.awt.Color(165, 167, 185) }, // Subtle warm tint
			{ 25.0,  new java.awt.Color(150, 165, 190) }, // Very subtle warmth
			{ 30.0,  new java.awt.Color(135, 165, 200) }, // Midday blue (natural)
			{ 50.0,  new java.awt.Color(125, 160, 195) }, // Clear blue (muted)
			{ 70.0,  new java.awt.Color(120, 155, 190) }, // High sun blue (subdued)
			{ 90.0,  new java.awt.Color(115, 150, 185) }  // Zenith blue (realistic)
		};
		
		// Get the enhanced color for current sun altitude (returns sRGB values)
		float[] enhancedColorSrgb = AtmosphereUtils.interpolateSrgb((float) sunAltitudeDegrees, skyColorKeyframes);
		// Convert to linear for blending
		float[] enhancedColor = rs117.hd.utils.ColorUtils.srgbToLinear(enhancedColorSrgb);
		
		// Calculate blend factor based sun altitude
		// Maintain regional character even during sunrise/sunset in gloomy areas
		float blendFactor;
		// Smooth continuous linear progression throughout, increased sunset blending starting earlier
		if (sunAltitudeDegrees >= 40) {
			// Very high sun - maximum regional influence (100% regional)
			blendFactor = 1.0f;
		} else if (sunAltitudeDegrees >= 10) {
			// High sun - strong regional influence (50-100% regional)
			blendFactor = (float) (0.50 + ((sunAltitudeDegrees - 10) / 30.0) * 0.50);
		} else if (sunAltitudeDegrees >= -3) {
			// Extended sunset period - gradual regional influence (0% to 50% regional)
			blendFactor = (float) ((sunAltitudeDegrees + 3) / 13.0) * 0.50f;
		} else {
			// Deep twilight/night - no regional influence (0% regional) to show pure dynamic colors
			blendFactor = 0.0f;
		}
		
		// Convert regional fog color from sRGB to linear RGB for proper blending
		float[] regionalLinear = rs117.hd.utils.ColorUtils.srgbToLinear(regionalFogColor);
		
		// Use the regional color directly without desaturation to preserve vivid colors
		float[] desaturatedRegional = regionalLinear;
		
		// Blend between enhanced color and desaturated regional color (in linear space)
		float[] resultLinear = new float[3];
		for (int i = 0; i < 3; i++) {
			resultLinear[i] = enhancedColor[i] * (1 - blendFactor) + desaturatedRegional[i] * blendFactor;
		}
		
		// Convert back to sRGB for return
		return rs117.hd.utils.ColorUtils.linearToSrgb(resultLinear);
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

		// Initialize on first call
		if (lastUpdateTime == 0) {
			lastUpdateTime = currentTimeMillis;
			lastDayLength = dayLength;
		}
		
		// Calculate elapsed real time since last update
		long realTimeElapsed = currentTimeMillis - lastUpdateTime;
		
		// Convert cycle duration from minutes to milliseconds for the full cycle
		double cycleDurationMillis = dayLength * 60.0 * 1000.0; // minutes to milliseconds
		
		// Calculate how much cycle time has progressed based on current day length
		double cycleTimeElapsed = realTimeElapsed / cycleDurationMillis;
		
		// Add to accumulated cycle time to maintain continuity
		accumulatedCycleTime += cycleTimeElapsed;
		
		// Keep cycle time within [0, 1) range
		accumulatedCycleTime = accumulatedCycleTime % 1.0;
		
		// Update tracking variables for next call
		lastUpdateTime = currentTimeMillis;
		lastDayLength = dayLength;
		
		double cyclePosition = accumulatedCycleTime;
		
		// Map cycle position to time of day with extended twilight periods
		// 0.0-0.15 = dawn/sunrise twilight (maps to 5am-7am)
		// 0.15-0.35 = morning (maps to 7am-12pm)
		// 0.35-0.55 = afternoon (maps to 12pm-5pm)
		// 0.55-0.70 = sunset twilight (maps to 5pm-7pm)
		// 0.70-0.85 = early night (maps to 7pm-12am)
		// 0.85-1.0 = late night/pre-dawn (maps to 12am-5am)
		double mappedHour;
		
		if (cyclePosition < 0.15) {
			// Dawn twilight: map 0-0.15 to 5am-7am (2 hours of twilight)
			mappedHour = 5.0 + (cyclePosition / 0.15) * 2.0;
		} else if (cyclePosition < 0.35) {
			// Morning: map 0.15-0.35 to 7am-12pm (5 hours)
			mappedHour = 7.0 + ((cyclePosition - 0.15) / 0.20) * 5.0;
		} else if (cyclePosition < 0.55) {
			// Afternoon: map 0.35-0.55 to 12pm-5pm (5 hours)
			mappedHour = 12.0 + ((cyclePosition - 0.35) / 0.20) * 5.0;
		} else if (cyclePosition < 0.70) {
			// Sunset twilight: map 0.55-0.70 to 5pm-7pm (2 hours of twilight)
			mappedHour = 17.0 + ((cyclePosition - 0.55) / 0.15) * 2.0;
		} else if (cyclePosition < 0.85) {
			// Early night: map 0.70-0.85 to 7pm-12am (5 hours)
			mappedHour = 19.0 + ((cyclePosition - 0.70) / 0.15) * 5.0;
		} else {
			// Late night/pre-dawn: map 0.85-1.0 to 12am-5am (5 hours)
			double lateNightProgress = (cyclePosition - 0.85) / 0.15;
			mappedHour = 0.0 + lateNightProgress * 5.0;
		}
		
		// Convert mapped hour to actual time
		Instant startOfDay = currentInstant.truncatedTo(ChronoUnit.DAYS);
		long mappedMillis = (long) (mappedHour * 60 * 60 * 1000);
		return startOfDay.plusMillis(mappedMillis);
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

	public static float getDynamicBrightnessMultiplier(double[] latLong, float dayLength, int minimumBrightness) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		
		// Calculate sun altitude in degrees (-90 to 90, where 90 is directly overhead)
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);
		
		// Convert minimum brightness from percentage to decimal
		float minBrightness = minimumBrightness / 100.0f;
		float horizonBrightness = minBrightness + 0.10f; // 10% brighter at horizon
		
		// Brightness based directly on sun altitude for perfect synchronization
		// When sun is below horizon (negative altitude), use playable night brightness
		if (sunAltitudeDegrees <= 0) {
			// Gradual transition from minimum (deep night) to horizon brightness
			double factor = Math.max(0, sunAltitudeDegrees + 18) / 18.0; // -18° to 0°
			return (float) (minBrightness + 0.10 * factor);
		} else {
			// When sun is above horizon, scale brightness from horizon to peak brightness
			// Use sine function to match natural sun intensity curve
			float peakBrightness = 1.2f; // 20% brighter than default at peak sun
			double sineFactor = Math.sin(Math.toRadians(sunAltitudeDegrees));
			return (float) (horizonBrightness + (peakBrightness - horizonBrightness) * sineFactor);
		}
	}

}
