package rs117.hd.scene.daylight_cycle;

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

import static rs117.hd.utils.MathUtils.*;

/** Complete sky definition resolved from a named preset and environment override. */
public class SkyConfiguration {
	public static final String DEFAULT_PRESET = "GIELINOR";

	@Nullable
	public String preset;
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
	public float starVisibility = 1;
	public float moonVisibility = 1;
	public float auroraVisibility = -1;
	public float nebulaVisibility = 1;
	public float moonSizeMult = 1;
	public float starHorizonHeight = 1;
	public float sunStrength = 1;
	public float sunriseSunsetStrength = 1;
	public float skyColorTakeoverAngle = 40;
	public float sunlightStrength = 1;
	public float minBrightnessBoost;
	public SkyGradient gradient;
	public SkyLightingProfile lighting;

	public SkyConfiguration normalize() {
		if (moonDiskColor == null)
			moonDiskColor = ColorUtils.colorTemperatureToLinearRgb(8000);
		if (moonLightColor == null)
			moonLightColor = moonDiskColor;
		if (nightSkyColor == null)
			nightSkyColor = moonDiskColor;
		if (auroraVisibility == -1)
			auroraVisibility = starVisibility;
		return this;
	}

	private static float[] interpolate(float[] out, float[] from, float[] to, float t) {
		int length = Math.max(from.length, to.length);
		if (out == null || out.length != length)
			out = new float[length];
		for (int i = 0; i < length; i++)
			out[i] = from[i % from.length] * (1 - t) + to[i % to.length] * t;
		return out;
	}

	/** Interpolate render controls without mutating either source configuration. */
	public SkyConfiguration interpolate(SkyConfiguration from, SkyConfiguration to, float t) {
		moonShadowStrength = from.moonShadowStrength * (1 - t) + to.moonShadowStrength * t;
		minMoonIllumination = from.minMoonIllumination * (1 - t) + to.minMoonIllumination * t;
		moonDiskColor = interpolate(moonDiskColor, from.moonDiskColor, to.moonDiskColor, t);
		moonLightColor = interpolate(moonLightColor, from.moonLightColor, to.moonLightColor, t);
		moonDiskStrength = from.moonDiskStrength * (1 - t) + to.moonDiskStrength * t;
		nightSkyColor = interpolate(nightSkyColor, from.nightSkyColor, to.nightSkyColor, t);
		nightSkyColorStrength = from.nightSkyColorStrength * (1 - t) + to.nightSkyColorStrength * t;
		starVisibility = from.starVisibility * (1 - t) + to.starVisibility * t;
		moonVisibility = from.moonVisibility * (1 - t) + to.moonVisibility * t;
		auroraVisibility = from.auroraVisibility * (1 - t) + to.auroraVisibility * t;
		nebulaVisibility = from.nebulaVisibility * (1 - t) + to.nebulaVisibility * t;
		moonSizeMult = from.moonSizeMult * (1 - t) + to.moonSizeMult * t;
		starHorizonHeight = from.starHorizonHeight * (1 - t) + to.starHorizonHeight * t;
		sunStrength = from.sunStrength * (1 - t) + to.sunStrength * t;
		sunriseSunsetStrength = from.sunriseSunsetStrength * (1 - t) + to.sunriseSunsetStrength * t;
		skyColorTakeoverAngle = from.skyColorTakeoverAngle * (1 - t) + to.skyColorTakeoverAngle * t;
		sunlightStrength = from.sunlightStrength * (1 - t) + to.sunlightStrength * t;
		minBrightnessBoost = from.minBrightnessBoost * (1 - t) + to.minBrightnessBoost * t;
		return this;
	}

	/**
	 * Merge JSON overrides into a resolved sky preset. Objects merge recursively; arrays and values replace.
	 */
	public static void merge(JsonObject target, JsonObject overrides) {
		for (var entry : overrides.entrySet()) {
			JsonElement value = entry.getValue();
			JsonElement existing = target.get(entry.getKey());
			if (existing != null && existing.isJsonObject() && value.isJsonObject())
				merge(existing.getAsJsonObject(), value.getAsJsonObject());
			else
				target.add(entry.getKey(), value.deepCopy());
		}
	}

	private static void removeMatching(JsonObject target, JsonObject base) {
		var iter = target.entrySet().iterator();
		while (iter.hasNext()) {
			var entry = iter.next();
			JsonElement baseValue = base.get(entry.getKey());
			if (baseValue == null)
				continue;
			JsonElement value = entry.getValue();
			if (value.isJsonObject() && baseValue.isJsonObject()) {
				removeMatching(value.getAsJsonObject(), baseValue.getAsJsonObject());
				if (value.getAsJsonObject().size() == 0)
					iter.remove();
			} else if (value.equals(baseValue)) {
				iter.remove();
			}
		}
	}

	public static class SkyGradient {
		public Keyframe[] zenith;
		public Keyframe[] horizon;
		public Keyframe[] sunGlow;
	}

	/** A value sampled at a sun altitude in degrees. */
	public static class Keyframe {
		public float altitude;
		@JsonAdapter(SrgbToLinearAdapter.class)
		public float[] color;
		public Float value;

		public float[] values() {
			return color != null ? color : vec(value);
		}
	}

	/** Tunable procedural lighting curves for the sky. */
	public static class SkyLightingProfile {
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

	@Slf4j
	public static class Adapter implements TypeAdapterFactory {
		@Override
		@SuppressWarnings("unchecked")
		public <T> TypeAdapter<T> create(com.google.gson.Gson gson, TypeToken<T> typeToken) {
			if (typeToken.getRawType() != SkyConfiguration.class)
				return null;

			TypeAdapter<SkyConfiguration> delegate = gson.getDelegateAdapter(this, TypeToken.get(SkyConfiguration.class));
			TypeAdapter<JsonElement> jsonElementAdapter = gson.getAdapter(JsonElement.class);
			return (TypeAdapter<T>) new TypeAdapter<SkyConfiguration>() {
				@Override
				public SkyConfiguration read(JsonReader in) throws IOException {
					JsonToken token = in.peek();
					if (token == JsonToken.NULL) {
						in.nextNull();
						return null;
					}

					String location = GsonUtils.location(in);
					JsonObject override;
					if (token == JsonToken.STRING) {
						override = new JsonObject();
						override.addProperty("preset", in.nextString());
					} else if (token == JsonToken.BEGIN_OBJECT) {
						override = new JsonParser().parse(in).getAsJsonObject();
					} else {
						log.error("Expected a sky preset or object at {}; ignoring value", location);
						in.skipValue();
						return null;
					}

					String preset = DEFAULT_PRESET;
					JsonElement presetElement = override.get("preset");
					if (presetElement != null) {
						if (!presetElement.isJsonPrimitive() || !presetElement.getAsJsonPrimitive().isString()) {
							log.error("Sky preset must be a string at {}; ignoring sky", location);
							return null;
						}
						preset = presetElement.getAsString();
					}

					JsonObject base = SkyManager.getPresetJson(preset);
					if (base == null) {
						log.error("Unknown sky preset '{}' at {}; ignoring sky", preset, location);
						return null;
					}
					JsonObject resolved = base.deepCopy();
					merge(resolved, override);
					if (override.has("moonDiskColor")) {
						if (!override.has("moonLightColor"))
							resolved.remove("moonLightColor");
						if (!override.has("moonDiskStrength"))
							resolved.remove("moonDiskStrength");
						if (!override.has("nightSkyColor"))
							resolved.remove("nightSkyColor");
					}
					resolved.addProperty("preset", preset);
					return delegate.fromJsonTree(resolved).normalize();
				}

				@Override
				public void write(JsonWriter out, SkyConfiguration sky) throws IOException {
					String preset = sky.preset == null ? DEFAULT_PRESET : sky.preset;
					JsonObject values = delegate.toJsonTree(sky).getAsJsonObject();
					values.remove("preset");
					JsonObject base = SkyManager.getPresetJson(preset);
					if (base != null) {
						SkyConfiguration baseConfiguration = delegate.fromJsonTree(base).normalize();
						JsonObject baseValues = delegate.toJsonTree(baseConfiguration).getAsJsonObject();
						baseValues.remove("preset");
						removeMatching(values, baseValues);
					}

					if (values.size() == 0) {
						out.value(preset);
						return;
					}

					JsonObject serialized = new JsonObject();
					serialized.addProperty("preset", preset);
					for (var entry : values.entrySet())
						serialized.add(entry.getKey(), entry.getValue());
					jsonElementAdapter.write(out, serialized);
				}
			};
		}
	}
}
