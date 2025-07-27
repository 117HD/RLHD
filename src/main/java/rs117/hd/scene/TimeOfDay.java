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

	public static float[] getAmbientColor(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		return AtmosphereUtils.getAmbientColor(modifiedDate.toEpochMilli(), latLong);
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
			// Deep night (sun well below horizon)
			{ -30.0, new java.awt.Color(8, 8, 16) },      // Very dark blue-black
			{ -20.0, new java.awt.Color(10, 12, 20) },    // Deep night
			{ -18.0, new java.awt.Color(12, 15, 24) },    // Astronomical twilight
			{ -15.0, new java.awt.Color(15, 20, 32) },    // Late astronomical twilight
			{ -12.0, new java.awt.Color(18, 25, 40) },    // Nautical twilight
			{ -10.0, new java.awt.Color(20, 28, 45) },    // Mid nautical twilight
			{ -8.0,  new java.awt.Color(22, 30, 50) },    // Late nautical twilight
			{ -7.0,  new java.awt.Color(23, 32, 55) },    // Pre-civil twilight
			{ -6.0,  new java.awt.Color(25, 35, 65) },    // Civil twilight start
			{ -5.5,  new java.awt.Color(27, 37, 67) },    // Early civil twilight
			{ -5.0,  new java.awt.Color(30, 40, 70) },    // Deep twilight blue
			{ -4.5,  new java.awt.Color(35, 45, 75) },    // Twilight blue progression
			{ -4.0,  new java.awt.Color(45, 55, 85) },    // Pre-dawn blue
			{ -3.5,  new java.awt.Color(52, 60, 87) },    // Blue-purple transition
			{ -3.0,  new java.awt.Color(60, 65, 90) },    // Twilight purple-blue
			{ -2.7,  new java.awt.Color(72, 67, 100) },   // Purple-blue blend
			{ -2.5,  new java.awt.Color(85, 70, 110) },   // Deep purple twilight
			{ -2.2,  new java.awt.Color(95, 72, 115) },   // Purple twilight progression
			{ -2.0,  new java.awt.Color(105, 75, 120) },  // Purple twilight
			{ -1.7,  new java.awt.Color(115, 80, 122) },  // Purple-pink blend
			{ -1.5,  new java.awt.Color(125, 85, 125) },  // Purple-pink transition
			{ -1.2,  new java.awt.Color(135, 90, 127) },  // Pink-purple progression
			{ -1.0,  new java.awt.Color(145, 95, 130) },  // Dawn pink-purple
			{ -0.8,  new java.awt.Color(165, 105, 125) }, // Warm pink
			{ -0.4,  new java.awt.Color(185, 115, 115) }, // Pink transition
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
			// Deep night (sun well below horizon)
			{ -30.0, new java.awt.Color(8, 8, 16) },      // Very dark blue-black
			{ -20.0, new java.awt.Color(10, 12, 20) },    // Deep night
			{ -18.0, new java.awt.Color(12, 15, 24) },    // Astronomical twilight
			{ -15.0, new java.awt.Color(15, 20, 32) },    // Late astronomical twilight
			{ -12.0, new java.awt.Color(18, 25, 40) },    // Nautical twilight
			{ -10.0, new java.awt.Color(20, 28, 45) },    // Mid nautical twilight
			{ -8.0,  new java.awt.Color(22, 30, 50) },    // Late nautical twilight
			{ -7.0,  new java.awt.Color(23, 32, 55) },    // Pre-civil twilight
			{ -6.0,  new java.awt.Color(25, 35, 65) },    // Civil twilight start
			{ -5.5,  new java.awt.Color(27, 37, 67) },    // Early civil twilight
			{ -5.0,  new java.awt.Color(30, 40, 70) },    // Deep twilight blue
			{ -4.5,  new java.awt.Color(35, 45, 75) },    // Twilight blue progression
			{ -4.0,  new java.awt.Color(45, 55, 85) },    // Pre-dawn blue
			{ -3.5,  new java.awt.Color(52, 60, 87) },    // Blue-purple transition
			{ -3.0,  new java.awt.Color(60, 65, 90) },    // Twilight purple-blue
			{ -2.7,  new java.awt.Color(72, 67, 100) },   // Purple-blue blend
			{ -2.5,  new java.awt.Color(85, 70, 110) },   // Deep purple twilight
			{ -2.2,  new java.awt.Color(95, 72, 115) },   // Purple twilight progression
			{ -2.0,  new java.awt.Color(105, 75, 120) },  // Purple twilight
			{ -1.7,  new java.awt.Color(115, 80, 122) },  // Purple-pink blend
			{ -1.5,  new java.awt.Color(125, 85, 125) },  // Purple-pink transition
			{ -1.2,  new java.awt.Color(135, 90, 127) },  // Pink-purple progression
			{ -1.0,  new java.awt.Color(145, 95, 130) },  // Dawn pink-purple
			{ -0.8,  new java.awt.Color(165, 105, 125) }, // Warm pink
			{ -0.4,  new java.awt.Color(185, 115, 115) }, // Pink transition
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
		// Use strong regional influence throughout most of the day
		// Only preserve pure enhanced colors during deep twilight/night
		float blendFactor;
		if (sunAltitudeDegrees >= 35) {
			// Very high sun - use very strong regional influence (85-95% regional)
			blendFactor = (float) Math.min(0.95, 0.85 + (sunAltitudeDegrees - 35) / 55.0 * 0.1);
		} else if (sunAltitudeDegrees >= 15) {
			// High sun - blend from moderate to strong regional (60-85% regional)
			blendFactor = (float) (0.6 + ((sunAltitudeDegrees - 15) / 20.0) * 0.25);
		} else if (sunAltitudeDegrees >= 5) {
			// Low sun - moderate regional influence (20-60% regional)
			blendFactor = (float) (0.2 + ((sunAltitudeDegrees - 5) / 10.0) * 0.4);
		} else {
			// Very low sun/twilight/night - minimal regional influence (0-20% regional)
			blendFactor = (float) Math.max(0.0, sunAltitudeDegrees / 5.0 * 0.2);
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

		// Calculate position in the cycle (0.0 to 1.0)
		long millisPerCycle = (long) (dayLength * 60 * 1000);
		double cyclePosition = (currentTimeMillis % millisPerCycle) / (double) millisPerCycle;
		
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

	public static float getDynamicBrightnessMultiplier(double[] latLong, float dayLength) {
		Instant modifiedDate = getModifiedDate(dayLength);
		double[] sunAngles = AtmosphereUtils.getSunAngles(modifiedDate.toEpochMilli(), latLong);
		
		// Calculate sun altitude in degrees (-90 to 90, where 90 is directly overhead)
		double sunAltitudeDegrees = Math.toDegrees(sunAngles[1]);
		
		// Brightness based directly on sun altitude for perfect synchronization
		// When sun is below horizon (negative altitude), use playable night brightness
		if (sunAltitudeDegrees <= 0) {
			// Gradual transition from 0.25 (deep night) to 0.4 (horizon) 
			// Increased from 0.1-0.2 to 0.25-0.4 for better gameplay visibility
			double factor = Math.max(0, sunAltitudeDegrees + 18) / 18.0; // -18° to 0°
			return (float) (0.25 + 0.15 * factor);
		} else {
			// When sun is above horizon, scale brightness from 0.4 to 1.0
			// Use sine function to match natural sun intensity curve
			double sineFactor = Math.sin(Math.toRadians(sunAltitudeDegrees));
			return (float) (0.4 + 0.6 * sineFactor);
		}
	}

	// Fixed time modes for different times of day
	public static float[] getFixedSunAngles(String timeMode) {
		switch (timeMode) {
			case "ALWAYS_DAY":
				// Sun at high noon (90 degrees altitude, 0 degrees azimuth)
				return new float[] { (float) Math.toRadians(90), 0 };
			case "ALWAYS_NIGHT":
				// Sun well below horizon (-30 degrees altitude)
				return new float[] { (float) Math.toRadians(-30), 0 };
			case "ALWAYS_SUNRISE":
				// Sun just above horizon (5 degrees altitude, east direction)
				return new float[] { (float) Math.toRadians(5), (float) Math.toRadians(90) };
			case "ALWAYS_SUNSET":
				// Sun earlier in sunset (12 degrees altitude, west direction)
				return new float[] { (float) Math.toRadians(12), (float) Math.toRadians(-90) };
			default:
				return new float[] { 0, 0 };
		}
	}

	public static float[] getFixedLightColor(String timeMode) {
		switch (timeMode) {
			case "ALWAYS_DAY":
				return AtmosphereUtils.interpolateSrgb(45.0f, new Object[][] {
					{ 30.0, new java.awt.Color(255, 255, 255) }  // Bright daylight
				});
			case "ALWAYS_NIGHT":
				return getNightLightColor();
			case "ALWAYS_SUNRISE":
				return AtmosphereUtils.interpolateSrgb(0.0f, new Object[][] {
					{ 0.0, new java.awt.Color(220, 140, 80) }  // Warm sunrise orange
				});
			case "ALWAYS_SUNSET":
				return AtmosphereUtils.interpolateSrgb(0.0f, new Object[][] {
					{ 0.0, new java.awt.Color(220, 140, 80) }  // Warm sunset orange
				});
			default:
				return new float[] { 1, 1, 1 };
		}
	}

	public static float[] getFixedAmbientColor(String timeMode) {
		switch (timeMode) {
			case "ALWAYS_DAY":
				return AtmosphereUtils.interpolateSrgb(45.0f, new Object[][] {
					{ 40.0, new java.awt.Color(185, 214, 255) }  // Bright day ambient
				});
			case "ALWAYS_NIGHT":
				return getNightAmbientColor();
			case "ALWAYS_SUNRISE":
				return AtmosphereUtils.interpolateSrgb(0.0f, new Object[][] {
					{ 0.0, new java.awt.Color(120, 85, 100) }  // Sunrise purple-pink ambient
				});
			case "ALWAYS_SUNSET":
				return AtmosphereUtils.interpolateSrgb(0.0f, new Object[][] {
					{ 0.0, new java.awt.Color(120, 85, 100) }  // Sunset purple-pink ambient
				});
			default:
				return new float[] { 1, 1, 1 };
		}
	}

	public static float[] getFixedSkyColor(String timeMode) {
		switch (timeMode) {
			case "ALWAYS_DAY":
				return AtmosphereUtils.interpolateSrgb(45.0f, new Object[][] {
					{ 45.0, new java.awt.Color(115, 150, 185) }  // Clear blue day sky
				});
			case "ALWAYS_NIGHT":
				return AtmosphereUtils.interpolateSrgb(-20.0f, new Object[][] {
					{ -20.0, new java.awt.Color(8, 8, 16) }  // Dark night sky
				});
			case "ALWAYS_SUNRISE":
				return AtmosphereUtils.interpolateSrgb(0.0f, new Object[][] {
					{ 0.0, new java.awt.Color(220, 140, 80) }  // Warm sunrise sky
				});
			case "ALWAYS_SUNSET":
				return AtmosphereUtils.interpolateSrgb(0.0f, new Object[][] {
					{ 0.0, new java.awt.Color(220, 140, 80) }  // Warm sunset sky
				});
			default:
				return new float[] { 1, 1, 1 };
		}
	}

	public static float getFixedBrightnessMultiplier(String timeMode) {
		switch (timeMode) {
			case "ALWAYS_DAY":
				return 1.0f;  // Full brightness
			case "ALWAYS_NIGHT":
				return 0.25f;  // Playable night brightness (increased from 0.1f)
			case "ALWAYS_SUNRISE":
			case "ALWAYS_SUNSET":
				return 0.6f;  // Golden hour brightness
			default:
				return 1.0f;
		}
	}
}
