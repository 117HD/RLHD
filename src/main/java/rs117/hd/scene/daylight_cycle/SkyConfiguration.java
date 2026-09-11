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
	public float moonShadowStrength = 1;
	public float minMoonIllumination;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonDiskColor;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] moonLightColor;
	public float moonDiskStrength = 1;
	@JsonAdapter(SrgbToLinearAdapter.class)
	public float[] nightSkyColor;
	public float nightSkyColorStrength = 1;
	public float skyVisibility = 1;
	public float moonVisibility = 1;
	public float starVisibility = -1;
	public float nebulaVisibility = 1;
	public float auroraVisibility = -1;
	public float moonSizeMult = 1;
	public float starHorizonHeight = 1;
	public float sunStrength = 1;
	public float sunriseSunsetStrength = 1;
	public float skyColorTakeoverAngle = 40;
	public float sunlightStrength = 1;
	public float minBrightnessBoost;

	public void normalize() {
		if (moonDiskColor == null)
			moonDiskColor = ColorUtils.colorTemperatureToLinearRgb(8000);
		if (moonLightColor == null)
			moonLightColor = moonDiskColor;
		if (nightSkyColor == null)
			nightSkyColor = moonDiskColor;
		if (starVisibility == -1)
			starVisibility = skyVisibility;
		if (auroraVisibility == -1)
			auroraVisibility = skyVisibility;

		if (sunAngles != null)
			sunAngles = HDUtils.ensureArrayLength(sunAngles, 2);
		if (moonAngles != null)
			moonAngles = HDUtils.ensureArrayLength(moonAngles, 2);
		moonDiskColor = HDUtils.ensureArrayLength(moonDiskColor, 3);
		moonLightColor = HDUtils.ensureArrayLength(moonLightColor, 3);
		nightSkyColor = HDUtils.ensureArrayLength(nightSkyColor, 3);
		if (profile == null ||
			profile.nightSkyColor == null ||
			profile.brightness == null ||
			profile.brightness.nightAltitude >= profile.brightness.lowSunAltitude ||
			profile.brightness.lowSunAltitude >= profile.brightness.horizonAltitude)
			throw new IllegalStateException("Invalid sky profile");
		profile.nightSkyColor = HDUtils.ensureArrayLength(profile.nightSkyColor, 3);
		normalizeKeyframes(profile.zenith, true);
		normalizeKeyframes(profile.horizon, true);
		normalizeKeyframes(profile.sunGlow, true);
		normalizeKeyframes(profile.ambientColor, true);
		normalizeKeyframes(profile.directionalTemperature, false);
		normalizeKeyframes(profile.regionalBlend, false);
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

	private static float[] interpolate(float[] out, float[] from, float[] to, float t) {
		int length = Math.max(from.length, to.length);
		if (out == null || out.length != length)
			out = new float[length];
		for (int i = 0; i < length; i++)
			out[i] = from[i % from.length] * (1 - t) + to[i % to.length] * t;
		return out;
	}

	/**
	 * Interpolate the properties evaluated outside of {@link SkyProfile}. Profile curves and celestial overrides
	 * are resolved separately.
	 */
	public SkyConfiguration interpolateLightingParameters(SkyConfiguration from, SkyConfiguration to, float t) {
		moonShadowStrength = mix(from.moonShadowStrength, to.moonShadowStrength, t);
		minMoonIllumination = mix(from.minMoonIllumination, to.minMoonIllumination, t);
		moonDiskColor = interpolate(moonDiskColor, from.moonDiskColor, to.moonDiskColor, t);
		moonLightColor = interpolate(moonLightColor, from.moonLightColor, to.moonLightColor, t);
		moonDiskStrength = mix(from.moonDiskStrength, to.moonDiskStrength, t);
		nightSkyColor = interpolate(nightSkyColor, from.nightSkyColor, to.nightSkyColor, t);
		nightSkyColorStrength = mix(from.nightSkyColorStrength, to.nightSkyColorStrength, t);
		skyVisibility = mix(from.skyVisibility, to.skyVisibility, t);
		moonVisibility = mix(from.moonVisibility, to.moonVisibility, t);
		starVisibility = mix(from.starVisibility, to.starVisibility, t);
		nebulaVisibility = mix(from.nebulaVisibility, to.nebulaVisibility, t);
		auroraVisibility = mix(from.auroraVisibility, to.auroraVisibility, t);
		moonSizeMult = mix(from.moonSizeMult, to.moonSizeMult, t);
		starHorizonHeight = mix(from.starHorizonHeight, to.starHorizonHeight, t);
		sunStrength = mix(from.sunStrength, to.sunStrength, t);
		sunriseSunsetStrength = mix(from.sunriseSunsetStrength, to.sunriseSunsetStrength, t);
		skyColorTakeoverAngle = mix(from.skyColorTakeoverAngle, to.skyColorTakeoverAngle, t);
		sunlightStrength = mix(from.sunlightStrength, to.sunlightStrength, t);
		minBrightnessBoost = mix(from.minBrightnessBoost, to.minBrightnessBoost, t);
		return this;
	}

	public static class SkyProfile {
		public Keyframe[] zenith;
		public Keyframe[] horizon;
		public Keyframe[] sunGlow;
		public Keyframe[] ambientColor;
		public Keyframe[] directionalTemperature;
		public Keyframe[] regionalBlend;
		@JsonAdapter(SrgbToLinearAdapter.class)
		public float[] nightSkyColor;
		public float directionalBaseTemperature;
		public float directionalBaseStrength;
		public BrightnessCurve brightness;

		public static class BrightnessCurve {
			public float nightAltitude;
			public float lowSunAltitude;
			public float horizonAltitude;
			public float lowSunBoost;
			public float horizonBoost;
			public float earlyDayBoost;
			public float daytimeStrength;
		}
	}

	public static class Keyframe {
		public float altitude;
		@JsonAdapter(SrgbToLinearAdapter.class)
		public float[] color;
		public Float value;

		public float[] values() {
			return color != null ? color : vec(value);
		}
	}

	@Slf4j
	public static class Adapter implements TypeAdapterFactory {
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

					JsonObject override = new JsonParser().parse(in).getAsJsonObject();

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
					JsonObject json = delegate.toJsonTree(sky).getAsJsonObject();
					var base = DEFAULT_PRESET;
					if (sky.parent != null)
						base = SkyManager.PRESETS.getOrDefault(sky.parent, base);
					JsonObject baseJson = delegate.toJsonTree(base).getAsJsonObject();
					GsonUtils.removeMatching(json, baseJson);
					if (json.size() == 0) {
						if (sky.parent == null || sky.parent.equals(DEFAULT_PRESET.name)) {
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
