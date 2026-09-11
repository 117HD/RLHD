package rs117.hd.scene.daylight_cycle;

import rs117.hd.scene.daylight_cycle.SkyConfiguration.SkyProfile;
import rs117.hd.utils.ColorUtils;

import static rs117.hd.utils.MathUtils.*;

/**
 * Celestial state is resolved by {@code SkyManager}; {@code SkyRenderer} then resolves shadow eligibility.
 */
public final class SkyState {
	/** Reusable output from the sky gradient and brightness curves. */
	public static final class LightingSample {
		public float[] zenithSrgb;
		public float[] horizonSrgb;
		public float[] sunGlowSrgb;
		public float[] horizonLinear;
		/** Environment fog before the sky gradient is applied. */
		public float[] referenceFogColorLinear;
		public float sunAltitudeDegrees;
		/** Celestial inputs populated by SkyManager when sampling an authored environment. */
		public float moonAltitudeDegrees;
		public float visibleMoonIllumination;
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
	/** Cycle shadow source, or the environment's shadow angles when the cycle is inactive. */
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

	public static void sampleLighting(LightingSample out, float sunAltitudeDegrees, SkyConfiguration sky, float[] fogColor, float minBrightness) {
		SkyProfile profile = sky.profile;
		float takeover = max(0, sky.skyColorTakeoverAngle);
		float[] zenith = profile.interpolate(sunAltitudeDegrees, profile.zenith);
		float[] horizon = profile.interpolate(sunAltitudeDegrees, profile.horizon);
		float[] sunGlow = profile.interpolate(sunAltitudeDegrees, profile.sunGlow);
		if (fogColor != null && sky.sunStrength < 1) {
			float window = sunAltitudeDegrees >= 0 ? 1 : smoothstep(-25, 0, sunAltitudeDegrees);
			float suppression = (1 - sky.sunStrength) * window;
			if (suppression > 0) {
				float[] target = mix(fogColor, profile.nightSkyColor, smoothstep(5, -5, sunAltitudeDegrees));
				blendSky(zenith, horizon, target, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null && sky.sunriseSunsetStrength < 1) {
			float window = sunAltitudeDegrees < 0 ? smoothstep(-15, 0, sunAltitudeDegrees) : takeover == 0 ? 0 : smoothstep(takeover, 0, sunAltitudeDegrees);
			float suppression = (1 - sky.sunriseSunsetStrength) * window;
			if (suppression > 0) {
				blendSky(zenith, horizon, fogColor, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null) {
			float blend = sunAltitudeDegrees < 0 ? 0 : takeover == 0 ? 1 : smoothstep(0, takeover, sunAltitudeDegrees);
			if (blend > 0)
				blendSky(zenith, horizon, fogColor, blend);
		}
		float nightBlend = smoothstep(0, -15, sunAltitudeDegrees);
		if (nightBlend > 0)
			blendSky(zenith, horizon, profile.nightSkyColor, nightBlend);
		out.zenithSrgb = ColorUtils.linearToSrgb(zenith);
		out.horizonSrgb = ColorUtils.linearToSrgb(horizon);
		out.sunGlowSrgb = ColorUtils.linearToSrgb(sunGlow);
		out.horizonLinear = ColorUtils.srgbToLinear(out.horizonSrgb);
		out.referenceFogColorLinear = fogColor;
		out.sunAltitudeDegrees = sunAltitudeDegrees;
		out.brightnessMultiplier = profile.getBrightnessMultiplier(sunAltitudeDegrees, minBrightness);
	}

	private static void blendSky(float[] zenith, float[] horizon, float[] color, float t) {
		mix(zenith, zenith, color, t);
		mix(horizon, horizon, color, t);
	}
}
