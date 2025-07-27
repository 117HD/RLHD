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
		
		// Calculate blend factor - similar to skybox but adjusted for lighting
		float blendFactor;
		if (sunAltitudeDegrees >= 25) {
			// High sun - use strong regional influence (70-85% regional)
			blendFactor = (float) Math.min(0.85, 0.70 + (sunAltitudeDegrees - 25) / 65.0 * 0.15);
		} else if (sunAltitudeDegrees >= 10) {
			// Medium sun - moderate regional influence (30-70% regional)
			blendFactor = (float) (0.30 + ((sunAltitudeDegrees - 10) / 15.0) * 0.40);
		} else if (sunAltitudeDegrees >= 0) {
			// Low sun - minimal regional influence (0-30% regional)
			blendFactor = (float) (sunAltitudeDegrees / 10.0 * 0.30);
		} else {
			// Night/twilight - no regional influence
			blendFactor = 0.0f;
		}
		
		// Convert regional color from sRGB to linear for blending
		float[] regionalLinear = rs117.hd.utils.ColorUtils.srgbToLinear(regionalDirectionalColor);
		
		// Blend the colors in linear space
		float[] blended = new float[3];
		for (int i = 0; i < 3; i++) {
			blended[i] = dynamicLight[i] * (1 - blendFactor) + regionalLinear[i] * blendFactor;
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
		
		// Calculate blend factor - similar to directional but slightly different curve
		float blendFactor;
		if (sunAltitudeDegrees >= 25) {
			// High sun - use strong regional influence (75-90% regional)
			blendFactor = (float) Math.min(0.90, 0.75 + (sunAltitudeDegrees - 25) / 65.0 * 0.15);
		} else if (sunAltitudeDegrees >= 10) {
			// Medium sun - moderate regional influence (40-75% regional)
			blendFactor = (float) (0.40 + ((sunAltitudeDegrees - 10) / 15.0) * 0.35);
		} else if (sunAltitudeDegrees >= 0) {
			// Low sun - minimal regional influence (10-40% regional)
			blendFactor = (float) (0.10 + (sunAltitudeDegrees / 10.0) * 0.30);
		} else {
			// Night/twilight - minimal regional influence (0-10% regional)
			blendFactor = (float) Math.max(0.0, (sunAltitudeDegrees + 10) / 10.0 * 0.10);
		}
		
		// Convert regional color from sRGB to linear for blending
		float[] regionalLinear = rs117.hd.utils.ColorUtils.srgbToLinear(regionalAmbientColor);
		
		// Blend the colors in linear space
		float[] blended = new float[3];
		for (int i = 0; i < 3; i++) {
			blended[i] = dynamicAmbient[i] * (1 - blendFactor) + regionalLinear[i] * blendFactor;
		}
		
		return blended;
	}

	public static float[] getSkyColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return AtmosphereUtils.getSkyColor(modifiedDate.toEpochMilli(), latLong);
	}

	public static float[] getEnhancedSkyColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		
		// Convert sun altitude to degrees (-90 to +90)
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);
		
		// Enhanced sky color palette with gradual sunset to night transition
		Object[][] skyColorKeyframes = {
			// Deep night (sun well below horizon) - smooth progression from dark to lighter
			{ -30.0, new java.awt.Color(25, 32, 50) },    // Deepest night (darkest)
			{ -20.0, new java.awt.Color(28, 36, 55) },    // Deep night
			{ -18.0, new java.awt.Color(30, 38, 58) },    // Late deep night
			{ -15.0, new java.awt.Color(33, 42, 62) },    // Early astronomical twilight
			{ -12.0, new java.awt.Color(36, 46, 68) },    // Astronomical twilight
			{ -10.0, new java.awt.Color(40, 50, 75) },    // Late astronomical twilight
			{ -8.0,  new java.awt.Color(42, 52, 78) },    // Late nautical twilight
			{ -7.0,  new java.awt.Color(45, 55, 82) },    // Pre-civil twilight
			{ -6.0,  new java.awt.Color(48, 58, 88) },    // Civil twilight start
			{ -5.5,  new java.awt.Color(50, 60, 92) },    // Early civil twilight
			{ -5.0,  new java.awt.Color(52, 62, 95) },    // Deep twilight blue
			{ -4.5,  new java.awt.Color(48, 58, 88) },    // Twilight blue progression
			{ -4.0,  new java.awt.Color(45, 55, 85) },    // Pre-dawn blue
			{ -3.5,  new java.awt.Color(52, 60, 87) },    // Blue-purple transition
			{ -3.0,  new java.awt.Color(60, 65, 90) },    // Twilight purple-blue
			{ -2.7,  new java.awt.Color(72, 67, 100) },   // Purple-blue blend
			{ -2.5,  new java.awt.Color(85, 70, 110) },   // Deep purple twilight
			{ -2.2,  new java.awt.Color(95, 72, 115) },   // Purple twilight progression
			{ -2.0,  new java.awt.Color(105, 75, 120) },  // Purple twilight
			{ -1.7,  new java.awt.Color(110, 85, 115) },  // Purple-pink blend (reduced pink)
			{ -1.5,  new java.awt.Color(115, 90, 118) },  // Purple-pink transition (more muted)
			{ -1.2,  new java.awt.Color(125, 95, 120) },  // Pink-purple progression (less intense)
			{ -1.0,  new java.awt.Color(135, 100, 122) }, // Dawn pink-purple (toned down)
			{ -0.8,  new java.awt.Color(150, 110, 118) }, // Warm pink (less saturated)
			{ -0.4,  new java.awt.Color(170, 120, 110) }, // Pink transition (more orange blend)
			{ 0.0,   new java.awt.Color(185, 122, 108) }, // Horizon transition
			{ 0.8,   new java.awt.Color(200, 125, 105) }, // Pink-orange blend
			{ 2.0,   new java.awt.Color(210, 135, 95) },  // Warm orange approaching
			{ 4.0,   new java.awt.Color(235, 150, 90) },  // Golden orange
			{ 6.0,   new java.awt.Color(245, 170, 105) }, // Strong golden hour
			{ 8.0,   new java.awt.Color(240, 185, 125) }, // Late golden hour
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
		
		return AtmosphereUtils.interpolateSrgb((float) sunAltitudeDegrees, skyColorKeyframes);
	}

	public static float[] getRegionalEnhancedSkyColor(double[] latLong, float dayLength, float[] regionalFogColor) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		
		// Convert sun altitude to degrees (-90 to +90)
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);
		
		// Enhanced sky color palette with gradual sunset to night transition
		Object[][] skyColorKeyframes = {
			// Deep night (sun well below horizon) - smooth progression from dark to lighter
			{ -30.0, new java.awt.Color(25, 32, 50) },    // Deepest night (darkest)
			{ -20.0, new java.awt.Color(28, 36, 55) },    // Deep night
			{ -18.0, new java.awt.Color(30, 38, 58) },    // Late deep night
			{ -15.0, new java.awt.Color(33, 42, 62) },    // Early astronomical twilight
			{ -12.0, new java.awt.Color(36, 46, 68) },    // Astronomical twilight
			{ -10.0, new java.awt.Color(40, 50, 75) },    // Late astronomical twilight
			{ -8.0,  new java.awt.Color(42, 52, 78) },    // Late nautical twilight
			{ -7.0,  new java.awt.Color(45, 55, 82) },    // Pre-civil twilight
			{ -6.0,  new java.awt.Color(48, 58, 88) },    // Civil twilight start
			{ -5.5,  new java.awt.Color(50, 60, 92) },    // Early civil twilight
			{ -5.0,  new java.awt.Color(52, 62, 95) },    // Deep twilight blue
			{ -4.5,  new java.awt.Color(48, 58, 88) },    // Twilight blue progression
			{ -4.0,  new java.awt.Color(45, 55, 85) },    // Pre-dawn blue
			{ -3.5,  new java.awt.Color(52, 60, 87) },    // Blue-purple transition
			{ -3.0,  new java.awt.Color(60, 65, 90) },    // Twilight purple-blue
			{ -2.7,  new java.awt.Color(72, 67, 100) },   // Purple-blue blend
			{ -2.5,  new java.awt.Color(85, 70, 110) },   // Deep purple twilight
			{ -2.2,  new java.awt.Color(95, 72, 115) },   // Purple twilight progression
			{ -2.0,  new java.awt.Color(105, 75, 120) },  // Purple twilight
			{ -1.7,  new java.awt.Color(110, 85, 115) },  // Purple-pink blend (reduced pink)
			{ -1.5,  new java.awt.Color(115, 90, 118) },  // Purple-pink transition (more muted)
			{ -1.2,  new java.awt.Color(125, 95, 120) },  // Pink-purple progression (less intense)
			{ -1.0,  new java.awt.Color(135, 100, 122) }, // Dawn pink-purple (toned down)
			{ -0.8,  new java.awt.Color(150, 110, 118) }, // Warm pink (less saturated)
			{ -0.4,  new java.awt.Color(170, 120, 110) }, // Pink transition (more orange blend)
			{ 0.0,   new java.awt.Color(185, 122, 108) }, // Horizon transition
			{ 0.8,   new java.awt.Color(200, 125, 105) }, // Pink-orange blend
			{ 2.0,   new java.awt.Color(210, 135, 95) },  // Warm orange approaching
			{ 4.0,   new java.awt.Color(235, 150, 90) },  // Golden orange
			{ 6.0,   new java.awt.Color(245, 170, 105) }, // Strong golden hour
			{ 8.0,   new java.awt.Color(240, 185, 125) }, // Late golden hour
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
		
		// Get the enhanced color for current sun altitude
		float[] enhancedColor = AtmosphereUtils.interpolateSrgb((float) sunAltitudeDegrees, skyColorKeyframes);
		
		// Calculate blend factor based sun altitude
		// Maintain regional character even during sunrise/sunset in gloomy areas
		float blendFactor;
		if (sunAltitudeDegrees >= 35) {
			// Very high sun - use very strong regional influence (85-95% regional)
			blendFactor = (float) Math.min(0.95, 0.85 + (sunAltitudeDegrees - 35) / 55.0 * 0.1);
		} else if (sunAltitudeDegrees >= 15) {
			// High sun - blend from moderate to strong regional (60-85% regional)
			blendFactor = (float) (0.6 + ((sunAltitudeDegrees - 15) / 20.0) * 0.25);
		} else if (sunAltitudeDegrees >= 5) {
			// Sunset/late sunrise - moderate regional influence (45-60% regional)
			blendFactor = (float) (0.45 + ((sunAltitudeDegrees - 5) / 10.0) * 0.15);
		} else if (sunAltitudeDegrees >= -2) {
			// Peak sunrise/sunset colors - maintain significant regional influence (30-45% regional)
			// This ensures gloomy areas retain their atmospheric character
			blendFactor = (float) (0.30 + ((sunAltitudeDegrees + 2) / 7.0) * 0.15);
		} else if (sunAltitudeDegrees >= -5) {
			// Early sunrise/late sunset - moderate regional influence (30% regional)
			blendFactor = 0.30f;
		} else if (sunAltitudeDegrees >= -20) {
			// Civil to deep twilight - gradually reduce regional influence (30-0% regional)
			blendFactor = (float) (0.30 * ((sunAltitudeDegrees + 20) / 15.0));
		} else {
			// Deep night - no regional influence (0% regional)
			blendFactor = 0.0f;
		}
		
		// Convert regional fog color from sRGB to linear RGB for proper blending
		float[] regionalLinear = rs117.hd.utils.ColorUtils.srgbToLinear(regionalFogColor);
		
		// Desaturate the regional color to make it more suitable for skybox
		float[] desaturatedRegional = new float[3];
		for (int i = 0; i < 3; i++) {
			// Calculate luminance for desaturation
			float luminance = regionalLinear[0] * 0.299f + regionalLinear[1] * 0.587f + regionalLinear[2] * 0.114f;
			// Blend 70% original color with 30% luminance to reduce saturation
			desaturatedRegional[i] = regionalLinear[i] * 0.7f + luminance * 0.3f;
		}
		
		// Blend between enhanced color and desaturated regional color
		float[] result = new float[3];
		for (int i = 0; i < 3; i++) {
			result[i] = enhancedColor[i] * (1 - blendFactor) + desaturatedRegional[i] * blendFactor;
		}
		
		return result;
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
