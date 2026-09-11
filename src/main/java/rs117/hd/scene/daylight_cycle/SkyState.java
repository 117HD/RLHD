package rs117.hd.scene.daylight_cycle;

import rs117.hd.scene.daylight_cycle.SkyConfiguration.SkyProfile;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.MathUtils.*;

/** Mutable per-frame sky snapshot. {@code SkyManager} resolves it before renderers consume it. */
public final class SkyState {
	/** Reusable output from the sky gradient and brightness curves. */
	public static final class LightingSample {
		public float[] zenithSrgb;
		public float[] horizonSrgb;
		public float[] sunGlowSrgb;
		public float[] horizonLinear;
		public float[] noonHorizonLinear;
		public float brightnessMultiplier;
	}

	/** Whether the current area renders with the daylight cycle rather than its environment lighting. */
	public boolean cycleActive;
	/** Whether resolved directional lighting has non-zero strength for shadow rendering. */
	public boolean castsShadows;
	public SkyConfiguration fromConfiguration;
	public SkyConfiguration toConfiguration;
	public float configurationTransition;
	public float moonDirectionalStrength;
	public float[] sunAngles;
	public float[] moonAngles;
	/** The sun while above the horizon, otherwise the moon while above it. */
	public float[] shadowAngles;
	public float[] sunDirection;
	public float[] moonDirection;
	public float[] moonPhaseLightDirection;
	public boolean moonPhaseReversed;
	public float[] moonLibration;
	public float[] celestialPole;
	public float celestialRotation;
	public float moonIllumination;
	public float sunAltitudeDegrees;
	public float moonAltitudeDegrees;
	public float moonVisibility;
	public float auroraStrength;

	public static void sampleLighting(
		LightingSample out, float sunAltitude, SkyProfile profile, float[] fogColor, float sunStrength,
		float sunriseSunsetStrength, float skyColorTakeoverAngle, float minBrightness
	) {
		float takeover = max(0, skyColorTakeoverAngle);
		float[] zenith = interpolate(sunAltitude, profile.zenith);
		float[] horizon = interpolate(sunAltitude, profile.horizon);
		float[] sunGlow = interpolate(sunAltitude, profile.sunGlow);
		if (fogColor != null && sunStrength < 1) {
			float window = sunAltitude >= 0 ? 1 : smoothstep(-25, 0, sunAltitude);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0) {
				float[] target = mix(fogColor, profile.nightSkyColor, smoothstep(5, -5, sunAltitude));
				blendSky(zenith, horizon, target, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null && sunriseSunsetStrength < 1) {
			float window = sunAltitude < 0 ? smoothstep(-15, 0, sunAltitude) : takeover == 0 ? 0 : smoothstep(takeover, 0, sunAltitude);
			float suppression = (1 - sunriseSunsetStrength) * window;
			if (suppression > 0) {
				blendSky(zenith, horizon, fogColor, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null) {
			float blend = sunAltitude < 0 ? 0 : takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitude);
			if (blend > 0)
				blendSky(zenith, horizon, fogColor, blend);
		}
		float nightBlend = smoothstep(0, -15, sunAltitude);
		if (nightBlend > 0)
			blendSky(zenith, horizon, profile.nightSkyColor, nightBlend);
		out.zenithSrgb = ColorUtils.linearToSrgb(zenith);
		out.horizonSrgb = ColorUtils.linearToSrgb(horizon);
		out.sunGlowSrgb = ColorUtils.linearToSrgb(sunGlow);
		out.brightnessMultiplier = getBrightnessMultiplier(sunAltitude, profile, minBrightness);
	}

	public static float getBrightnessMultiplier(float sunAltitudeDegrees, SkyProfile profile, float minBrightness) {
		var curve = profile.brightness;
		float horizonBrightness = minBrightness + curve.horizonBoost;
		if (sunAltitudeDegrees <= curve.nightAltitude)
			return minBrightness;
		if (sunAltitudeDegrees <= curve.lowSunAltitude) {
			float lowSunBrightness = minBrightness + curve.lowSunBoost;
			return mix(minBrightness, lowSunBrightness, smoothstep(curve.nightAltitude, curve.lowSunAltitude, sunAltitudeDegrees));
		}
		if (sunAltitudeDegrees <= curve.horizonAltitude) {
			float lowSunBrightness = minBrightness + curve.lowSunBoost;
			float earlyDayBrightness = horizonBrightness + curve.earlyDayBoost;
			return mix(lowSunBrightness, earlyDayBrightness, smoothstep(curve.lowSunAltitude, curve.horizonAltitude, sunAltitudeDegrees));
		}
		float earlyDayBrightness = horizonBrightness + curve.earlyDayBoost;
		float sineAtHorizon = sin(curve.horizonAltitude * DEG_TO_RAD);
		float normalizedSine = max(0, (sin(sunAltitudeDegrees * DEG_TO_RAD) - sineAtHorizon) / (1 - sineAtHorizon));
		return mix(earlyDayBrightness, curve.daytimeStrength, normalizedSine);
	}

	public static float[] interpolate(float x, SkyConfiguration.Keyframe[] keyframes) {
		int end = keyframes.length - 1;
		int i = 0;
		while (i < end && x > keyframes[i + 1].altitude)
			i++;
		SkyConfiguration.Keyframe from = keyframes[i];
		if (i == end)
			return copy(from.values());
		SkyConfiguration.Keyframe to = keyframes[i + 1];
		return mix(from.values(), to.values(), clamp((x - from.altitude) / (to.altitude - from.altitude), 0, 1));
	}

	private static void blendSky(float[] zenith, float[] horizon, float[] color, float t) {
		mix(zenith, zenith, color, t);
		mix(horizon, horizon, color, t);
	}
}
