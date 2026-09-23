package rs117.hd.scene.daylight_cycle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.config.MoonPhase;
import rs117.hd.scene.SkyManager;
import rs117.hd.scene.daylight_cycle.SkyState.GradientSample;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.ColorUtils.SrgbToLinearAdapter;
import rs117.hd.utils.GsonUtils;
import rs117.hd.utils.GsonUtils.DegreesToRadians;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.MathUtils.*;

public class SkyConfiguration {
	public static SkyConfiguration DEFAULT_PRESET;

	@Nullable
	public String name;
	@Nullable
	public String parent;
	public SkyProfile profile;
	public boolean customGradient;
	public float horizonWidth = 15;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] sunAngles;
	@Nullable
	@JsonAdapter(DegreesToRadians.class)
	public float[] moonAngles;
	public boolean hideMoon;
	@Nullable
	public MoonPhase forceMoonPhase;
	public float moonDirectionalStrength = -1;
	public float moonAmbientStrength = -1;
	public float moonShadowStrength = 1;
	public float minMoonIllumination;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonDiskColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonDirectionalColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonAmbientColor;
	public float moonDiskStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	@Nullable
	public float[] skyFogColor;
	public float skyFogColorMix = 1;
	public float skyFogDensity = -1;
	public float skyVisibility = 1;
	/** Negative means automatic; an explicit value can retain moonlight with hideMoon. */
	public float moonLightVisibility = -1;
	public float moonVisibility = 1;
	public float starVisibility = 1;
	public float nebulaVisibility = 1;
	public float auroraVisibility = 1;
	public float moonSizeMult = 1;
	public float starHorizonHeight = 1;
	private float sunStrength = 1;
	private float sunriseSunsetStrength = 1;
	private float skyColorTakeoverAngle = 40;
	public float sunlightStrength = 1;
	public float minBrightnessBoost;

	public void normalize() {
		if (moonDiskColor == null)
			moonDiskColor = ColorUtils.colorTemperatureToLinearRgb(8000);
		moonDiskColor = HDUtils.ensureArrayLength(moonDiskColor, 3);

		boolean deriveDirectional = moonDirectionalColor == null;
		if (deriveDirectional) {
			moonDirectionalColor = copy(moonDiskColor);
		} else {
			moonDirectionalColor = HDUtils.ensureArrayLength(moonDirectionalColor, 3);
		}
		if (moonDirectionalStrength < 0)
			moonDirectionalStrength = moonDiskStrength;

		boolean deriveAmbient = moonAmbientColor == null;
		if (deriveAmbient) {
			moonAmbientColor = copy(moonDirectionalColor);
			if (!deriveDirectional) {
				// We assume that authored directional colors already include the mesopic shift.
				// Undo this before deriving ambient lighting from it
				ColorUtils.invertMesopicShift(moonAmbientColor);
			}
			ColorUtils.deriveAmbientLight(moonAmbientColor, moonAmbientColor);
		} else {
			moonAmbientColor = HDUtils.ensureArrayLength(moonAmbientColor, 3);
		}
		if (moonAmbientStrength < 0)
			moonAmbientStrength = moonDirectionalStrength;

		// Apply mesopic shifts to derived night colors, since our renderer does not account for this in later stages
		if (deriveDirectional)
			ColorUtils.applyMesopicShift(moonDirectionalColor);
		if (deriveAmbient)
			ColorUtils.applyMesopicShift(moonAmbientColor);

		if (sunAngles != null)
			sunAngles = HDUtils.ensureArrayLength(sunAngles, 2);
		if (moonAngles != null)
			moonAngles = HDUtils.ensureArrayLength(moonAngles, 2);
		if (skyFogColor != null)
			skyFogColor = HDUtils.ensureArrayLength(skyFogColor, 3);
		if (profile == null)
			throw new IllegalStateException("Invalid sky profile");
		profile.normalize();
	}

	public static class SkyProfile {
		private Keyframe[] zenith;
		private Keyframe[] horizon;
		private Keyframe[] sunGlow;
		private Keyframe[] ambientColor;
		private Keyframe[] directionalTemperature;
		private Keyframe[] regionalBlend;
		@JsonAdapter(SrgbToLinearAdapter.class)
		private float[] nightSkyColor;
		private float directionalBaseTemperature;
		private float directionalBaseStrength;
		private BrightnessCurve brightness;

		private static class Keyframe {
			private float altitude;
			@JsonAdapter(SrgbToLinearAdapter.class)
			private float[] color;
			private Float value;

			private float[] values() {
				return color != null ? color : vec(value);
			}
		}

		private static class BrightnessCurve {
			private float nightAltitude;
			private float lowSunAltitude;
			private float horizonAltitude;
			private float lowSunBoost;
			private float horizonBoost;
			private float earlyDayBoost;
			private float daytimeStrength;
		}

		public void normalize() {
			if (nightSkyColor == null ||
				brightness == null ||
				brightness.nightAltitude >= brightness.lowSunAltitude ||
				brightness.lowSunAltitude >= brightness.horizonAltitude)
				throw new IllegalStateException("Invalid sky profile");
			nightSkyColor = HDUtils.ensureArrayLength(nightSkyColor, 3);
			normalizeKeyframes(zenith, true);
			normalizeKeyframes(horizon, true);
			normalizeKeyframes(sunGlow, true);
			normalizeKeyframes(ambientColor, true);
			normalizeKeyframes(directionalTemperature, false);
			normalizeKeyframes(regionalBlend, false);
		}

		private static void normalizeKeyframes(@Nullable Keyframe[] keyframes, boolean colors) {
			if (keyframes == null || keyframes.length == 0)
				throw new IllegalStateException("Missing sky keyframes");
			float previousAltitude = Float.NEGATIVE_INFINITY;
			for (int i = 0; i < keyframes.length; i++) {
				Keyframe keyframe = keyframes[i];
				if (keyframe == null || keyframe.altitude <= previousAltitude)
					throw new IllegalStateException("Sky keyframes must be ordered by altitude");
				if (colors) {
					if (keyframe.value != null || keyframe.color == null)
						throw new IllegalStateException("Expected a sky color keyframe");
					keyframe.color = HDUtils.ensureArrayLength(keyframe.color, 3);
				} else if (keyframe.color != null || keyframe.value == null) {
					throw new IllegalStateException("Expected a scalar sky keyframe");
				}
				previousAltitude = keyframe.altitude;
			}
		}

		public float[] getDirectionalLight(float sunAltitude) {
			float[] directionalLight = ColorUtils.colorTemperatureToLinearRgb(directionalBaseTemperature);
			multiply(directionalLight, directionalLight, directionalBaseStrength);
			if (sunAltitude >= 0) {
				float temperature = interpolate(sunAltitude * RAD_TO_DEG, directionalTemperature)[0];
				float strength = sin(sunAltitude);
				strength *= strength * 3;
				add(directionalLight, directionalLight, multiply(ColorUtils.colorTemperatureToLinearRgb(temperature), strength));
			}
			return directionalLight;
		}

		public float[] getAmbientLight(float sunAltitudeDegrees) {
			return interpolate(sunAltitudeDegrees, ambientColor);
		}

		public float getRegionalBlend(float sunAltitudeDegrees) {
			return interpolate(sunAltitudeDegrees, regionalBlend)[0];
		}

		private static float[] interpolate(float altitude, Keyframe[] keyframes) {
			int end = keyframes.length - 1;
			int i = 0;
			while (i < end && altitude > keyframes[i + 1].altitude)
				i++;
			Keyframe from = keyframes[i];
			if (i == end)
				return copy(from.values());
			Keyframe to = keyframes[i + 1];
			return mix(from.values(), to.values(), saturate((altitude - from.altitude) / (to.altitude - from.altitude)));
		}

		private float getBrightnessMultiplier(float sunAltitude) {
			// Scene exposure is applied after lighting is composed; this curve describes the sources.
			float minBrightness = 1;
			if (sunAltitude <= brightness.nightAltitude)
				return minBrightness;
			float lowSunBrightness = minBrightness + brightness.lowSunBoost;
			if (sunAltitude <= brightness.lowSunAltitude)
				return mix(minBrightness, lowSunBrightness, smoothstep(brightness.nightAltitude, brightness.lowSunAltitude, sunAltitude));
			float earlyDayBrightness = minBrightness + brightness.horizonBoost + brightness.earlyDayBoost;
			if (sunAltitude <= brightness.horizonAltitude)
				return mix(
					lowSunBrightness,
					earlyDayBrightness,
					smoothstep(brightness.lowSunAltitude, brightness.horizonAltitude, sunAltitude)
				);
			float sineAtHorizon = sin(brightness.horizonAltitude * DEG_TO_RAD);
			float normalizedSine = max(0, (sin(sunAltitude * DEG_TO_RAD) - sineAtHorizon) / (1 - sineAtHorizon));
			return mix(earlyDayBrightness, brightness.daytimeStrength, normalizedSine);
		}
	}

	/**
	 * Interpolate the properties evaluated outside of {@link SkyProfile}.
	 * Profile curves and celestial overrides are resolved separately.
	 */
	public SkyConfiguration interpolateLightingParameters(SkyConfiguration from, SkyConfiguration to, float t) {
		moonShadowStrength = mix(from.moonShadowStrength, to.moonShadowStrength, t);
		if (moonDiskColor == null)
			moonDiskColor = new float[3];
		mix(moonDiskColor, from.moonDiskColor, to.moonDiskColor, t);
		if (moonDirectionalColor == null)
			moonDirectionalColor = new float[3];
		mix(moonDirectionalColor, from.moonDirectionalColor, to.moonDirectionalColor, t);
		if (moonAmbientColor == null)
			moonAmbientColor = new float[3];
		mix(moonAmbientColor, from.moonAmbientColor, to.moonAmbientColor, t);
		moonDiskStrength = mix(from.moonDiskStrength, to.moonDiskStrength, t);
		moonDirectionalStrength = mix(from.moonDirectionalStrength, to.moonDirectionalStrength, t);
		moonAmbientStrength = mix(from.moonAmbientStrength, to.moonAmbientStrength, t);
		horizonWidth = mix(from.horizonWidth, to.horizonWidth, t);
		// Sky fog defaults are resolved against the environment before interpolation by the renderer.
		starVisibility = mix(from.starVisibility, to.starVisibility, t);
		nebulaVisibility = mix(from.nebulaVisibility, to.nebulaVisibility, t);
		auroraVisibility = mix(from.auroraVisibility, to.auroraVisibility, t);
		moonSizeMult = mix(from.moonSizeMult, to.moonSizeMult, t);
		starHorizonHeight = mix(from.starHorizonHeight, to.starHorizonHeight, t);
		sunlightStrength = mix(from.sunlightStrength, to.sunlightStrength, t);
		minBrightnessBoost = mix(from.minBrightnessBoost, to.minBrightnessBoost, t);
		return this;
	}

	public void evaluateGradient(GradientSample out, float sunAltitudeDegrees, float[] fogColor) {
		float takeover = max(0, skyColorTakeoverAngle);
		float[] zenith = SkyProfile.interpolate(sunAltitudeDegrees, profile.zenith);
		float[] horizon = SkyProfile.interpolate(sunAltitudeDegrees, profile.horizon);
		float[] sunGlow = SkyProfile.interpolate(sunAltitudeDegrees, profile.sunGlow);
		out.zenithLinear = zenith;
		out.horizonLinear = horizon;
		out.sunGlowLinear = sunGlow;
		out.brightnessMultiplier = profile.getBrightnessMultiplier(sunAltitudeDegrees);
		// Authored gradients bypass the automatic fog takeover and night-color replacement.
		if (customGradient)
			return;
		if (fogColor != null && sunStrength < 1) {
			float window = smoothstep(-25, 0, sunAltitudeDegrees);
			float suppression = (1 - sunStrength) * window;
			if (suppression > 0) {
				float[] target = mix(fogColor, profile.nightSkyColor, smoothstep(5, -5, sunAltitudeDegrees));
				blendSky(zenith, horizon, target, suppression);
				multiply(sunGlow, sunGlow, 1 - suppression);
			}
		}
		if (fogColor != null && sunriseSunsetStrength < 1) {
			float window = sunAltitudeDegrees < 0 ?
				smoothstep(-15, 0, sunAltitudeDegrees) :
				takeover == 0 ? 0 : smoothstep(takeover, 0, sunAltitudeDegrees);
			float suppression = (1 - sunriseSunsetStrength) * window;
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
	}

	private static void blendSky(float[] zenith, float[] horizon, float[] color, float t) {
		mix(zenith, zenith, color, t);
		mix(horizon, horizon, color, t);
	}

	@Slf4j
	public static class Adapter implements TypeAdapterFactory {
		private final JsonParser JSON_ELEMENT_PARSER = new JsonParser();

		@Override
		@SuppressWarnings("unchecked")
		public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
			if (typeToken.getRawType() != SkyConfiguration.class)
				return null;

			TypeAdapter<SkyConfiguration> delegate = gson.getDelegateAdapter(this, TypeToken.get(SkyConfiguration.class));
			TypeAdapter<JsonElement> jsonElementAdapter = gson.getAdapter(JsonElement.class);
			return (TypeAdapter<T>) new TypeAdapter<SkyConfiguration>() {
				@Nullable
				private SkyConfiguration resolveParent(String name, String location) {
					SkyConfiguration parent = SkyManager.PRESETS.get(name);
					if (parent == null)
						log.error("Unknown sky parent '{}' at {}; ignoring sky", name, location);
					return parent;
				}

				@Override
				public SkyConfiguration read(JsonReader in) throws IOException {
					JsonToken token = in.peek();
					if (token == JsonToken.NULL) {
						in.nextNull();
						return null;
					}

					String location = GsonUtils.location(in);
					if (token == JsonToken.STRING)
						return resolveParent(in.nextString(), location);

					if (token != JsonToken.BEGIN_OBJECT) {
						log.error("Expected a sky preset or object at {}; ignoring value", location);
						in.skipValue();
						return null;
					}

					JsonObject override = JSON_ELEMENT_PARSER.parse(in).getAsJsonObject();

					JsonElement parentElement = override.get("parent");
					SkyConfiguration parent = DEFAULT_PRESET;
					if (parentElement != null) {
						if (!parentElement.isJsonPrimitive() || !parentElement.getAsJsonPrimitive().isString()) {
							log.error("Sky parent must be a string at {}; ignoring sky", location);
							return null;
						}
						parent = resolveParent(parentElement.getAsString(), location);
						if (parent == null)
							return null;
						if (override.size() == 1)
							return parent;
					}

					if (parent == null) {
						log.error("No default sky preset at {}; ignoring sky", location);
						return null;
					}
					var parentJson = delegate.toJsonTree(parent).getAsJsonObject();
					GsonUtils.removeNulls(parentJson);
					parentJson.remove("name");
					parentJson.remove("parent");
					GsonUtils.deepInheritFrom(override, parentJson);
					try {
						return delegate.fromJsonTree(override);
					} catch (RuntimeException ex) {
						log.error("Invalid sky configuration at {}; ignoring sky: {}", location, ex.getMessage());
						return null;
					}
				}

				@Override
				public void write(JsonWriter out, SkyConfiguration sky) throws IOException {
					if (sky == null) {
						out.nullValue();
						return;
					}
					JsonObject json = delegate.toJsonTree(sky).getAsJsonObject();
					var base = DEFAULT_PRESET;
					if (sky.parent != null)
						base = SkyManager.PRESETS.getOrDefault(sky.parent, base);
					if (base == null) {
						jsonElementAdapter.write(out, json);
						return;
					}
					JsonObject baseJson = delegate.toJsonTree(base).getAsJsonObject();
					GsonUtils.removeMatching(json, baseJson);
					if (json.size() == 0) {
						if (sky.parent == null || base == DEFAULT_PRESET) {
							out.nullValue();
						} else {
							out.value(sky.parent);
						}
					} else {
						jsonElementAdapter.write(out, json);
					}
				}
			};
		}
	}
}
