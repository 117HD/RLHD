/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles;

import javax.annotation.Nullable;

public class ParticleDefinition {
	public static final float NO_TARGET = -1f;

	public String id;
	@Nullable
	public String description;
	public int heightOffset = 10;
	public int directionYaw = 1248;
	public int directionPitch = 30;

	public float spreadYawMin;
	public float spreadYawMax;
	public float spreadPitchMin;
	public float spreadPitchMax;
	public float minSpeed;
	public float maxSpeed;
	public int distanceFalloffType;
	public int distanceFalloffStrength;
	public float minScale;
	public float maxScale;
	public int minColourArgb;
	public int maxColourArgb;
	@Nullable
	public String minColour;
	@Nullable
	public String maxColour;
	@Nullable
	public String targetColour;
	public int minEmissionDelay;
	public int maxEmissionDelay;
	public int minSpawnCount;
	public int maxSpawnCount;
	@Nullable
	public int[] localEffectorFilter;
	@Nullable
	public int[] embeddedEffectors;
	public int upperBoundLevel = -2;
	public int lowerBoundLevel = -2;
	public int initialSpawnCount;
	@Nullable
	public String texture;
	public int flipbookColumns;
	public int flipbookRows;
	@Nullable
	public String flipbookMode;
	public boolean emitOnlyBeforeTime = true;
	public int emissionTimeThreshold = -1;
	public int emissionCycleDuration = -1;
	public boolean loopEmission = true;
	public int fallbackEmitterType = -1;
	@Nullable
	public ParticleDefinition fallbackDefinition;
	public int targetColourArgb;
	public int minGraphicsQuality;
	public int colourTransitionPercent = 100;
	public int alphaTransitionPercent = 100;
	public float targetSpeed = NO_TARGET;
	public int speedTransitionPercent = 100;
	public boolean uniformColourVariation = true;
	@Nullable
	public int[] globalEffectors;
	public float targetScale = NO_TARGET;
	public int scaleTransitionPercent = 100;
	public boolean forceTextureOnSoftwareRenderer;
	public boolean useSceneAmbientLight = true;
	public boolean collidesWithObjects;
	public boolean clipToTerrain = true;
	public boolean displayWhenCulled = false;

	public boolean hasLevelBounds;
	public int startRed, endRed, deltaRed;
	public int startGreen, endGreen, deltaGreen;
	public int startBlue, endBlue, deltaBlue;
	public int startAlpha, endAlpha, deltaAlpha;
	public int scaleTransitionTicks, scaleIncrementPerTick;
	public int targetScaleRef;
	public int speedTransitionTicks, speedIncrementPerTick;
	public int alphaTransitionTicks, colourTransitionTicks;
	public int redIncrementPerTick, greenIncrementPerTick, blueIncrementPerTick, alphaIncrementPerTick;

	private static final float TICKS_TO_SEC = 64f;
	private static final float U8 = 256f * 256f;
	@Nullable
	public float[] colourIncrementPerSecond;
	public float colourTransitionSecondsConstant;
	public float scaleIncrementPerSecondCached;
	public float scaleTransitionSecondsConstant;
	public float speedIncrementPerSecondCached;
	public float speedTransitionSecondsConstant;

	public void postDecode() {

		if (upperBoundLevel > -2 || lowerBoundLevel > -2) {
			hasLevelBounds = true;
		}
		startRed = (minColourArgb >> 16) & 0xff;
		endRed = (maxColourArgb >> 16) & 0xff;
		deltaRed = endRed - startRed;
		startGreen = (minColourArgb >> 8) & 0xff;
		endGreen = (maxColourArgb >> 8) & 0xff;
		deltaGreen = endGreen - startGreen;
		startBlue = minColourArgb & 0xff;
		endBlue = maxColourArgb & 0xff;
		deltaBlue = endBlue - startBlue;
		startAlpha = (minColourArgb >> 24) & 0xff;
		endAlpha = (maxColourArgb >> 24) & 0xff;
		deltaAlpha = endAlpha - startAlpha;

		if (targetScale >= 0f) {
			scaleTransitionTicks = scaleTransitionPercent * maxEmissionDelay / 100;
			if (scaleTransitionTicks == 0) {
				scaleTransitionTicks = 1;
			}
			targetScaleRef = (int) Math.round(targetScale / 4f * 16384f);
			float midScaleRef = (minScale + maxScale) / 2f / 4f * 16384f;
			scaleIncrementPerTick = (int) Math.round((targetScaleRef - midScaleRef) / scaleTransitionTicks);
		}
		if (targetSpeed >= 0f) {
			speedTransitionTicks = maxEmissionDelay * speedTransitionPercent / 100;
			if (speedTransitionTicks == 0) {
				speedTransitionTicks = 1;
			}
			speedIncrementPerTick = (int) Math.round((targetSpeed - (minSpeed + maxSpeed) / 2f) / speedTransitionTicks);
		}
		if (targetColourArgb != 0) {
			alphaTransitionTicks = alphaTransitionPercent * maxEmissionDelay / 100;
			colourTransitionTicks = maxEmissionDelay * colourTransitionPercent / 100;
			if (colourTransitionTicks == 0) {
				colourTransitionTicks = 1;
			}
			if (alphaTransitionTicks == 0) {
				alphaTransitionTicks = 1;
			}
			int targetRed = (targetColourArgb >> 16) & 0xff;
			int targetGreen = (targetColourArgb >> 8) & 0xff;
			int targetBlue = targetColourArgb & 0xff;
			int targetAlpha = (targetColourArgb >> 24) & 0xff;
			redIncrementPerTick = ((targetRed - (startRed + deltaRed / 2)) << 8) / colourTransitionTicks;
			greenIncrementPerTick = ((targetGreen - (startGreen + deltaGreen / 2)) << 8) / colourTransitionTicks;
			blueIncrementPerTick = ((targetBlue - (startBlue + deltaBlue / 2)) << 8) / colourTransitionTicks;
			alphaIncrementPerTick = ((targetAlpha - (startAlpha + deltaAlpha / 2)) << 8) / alphaTransitionTicks;
			redIncrementPerTick += (redIncrementPerTick <= 0 ? 4 : -4);
			greenIncrementPerTick += (greenIncrementPerTick <= 0 ? 4 : -4);
			blueIncrementPerTick += (blueIncrementPerTick <= 0 ? 4 : -4);
			alphaIncrementPerTick += (alphaIncrementPerTick <= 0 ? 4 : -4);
			colourIncrementPerSecond = new float[] {
				redIncrementPerTick * TICKS_TO_SEC / U8,
				greenIncrementPerTick * TICKS_TO_SEC / U8,
				blueIncrementPerTick * TICKS_TO_SEC / U8,
				alphaIncrementPerTick * TICKS_TO_SEC / U8
			};
			colourTransitionSecondsConstant = colourTransitionTicks / TICKS_TO_SEC;
		}
		if (targetScale >= 0f) {
			scaleIncrementPerSecondCached = scaleIncrementPerTick * TICKS_TO_SEC / 16384f * 4f;
			scaleTransitionSecondsConstant = scaleTransitionTicks / TICKS_TO_SEC;
		}
		if (targetSpeed >= 0f) {
			speedIncrementPerSecondCached = speedIncrementPerTick * TICKS_TO_SEC / 16384f;
			speedTransitionSecondsConstant = speedTransitionTicks / TICKS_TO_SEC;
		}
	}

	public static int hexToArgb(String hex) {
		if (hex == null || hex.isEmpty()) return 0;
		String s = hex.startsWith("#") ? hex.substring(1) : hex;
		if (s.length() != 6 && s.length() != 8) return 0;
		try {
			int r = Integer.parseInt(s.substring(0, 2), 16);
			int g = Integer.parseInt(s.substring(2, 4), 16);
			int b = Integer.parseInt(s.substring(4, 6), 16);
			int a = s.length() == 8 ? Integer.parseInt(s.substring(6, 8), 16) : 255;
			return (a << 24) | (r << 16) | (g << 8) | b;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public static String argbToHex(int argb) {
		int r = (argb >> 16) & 0xff;
		int g = (argb >> 8) & 0xff;
		int b = argb & 0xff;
		int a = (argb >> 24) & 0xff;
		return String.format("#%02x%02x%02x%02x", r, g, b, a);
	}

	public void parseHexColours() {
		if (minColour != null) {
			int argb = hexToArgb(minColour);
			if (argb != 0) minColourArgb = argb;
		}
		if (maxColour != null) {
			int argb = hexToArgb(maxColour);
			if (argb != 0) maxColourArgb = argb;
		}
		if (targetColour != null) {
			targetColourArgb = hexToArgb(targetColour);
		}
	}

	public static float[] argbToFloat(int argb) {
		return new float[] {
			((argb >> 16) & 0xff) / 255f,
			((argb >> 8) & 0xff) / 255f,
			(argb & 0xff) / 255f,
			((argb >> 24) & 0xff) / 255f
		};
	}
}
