package rs117.hd.tests;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.scene.daylight_cycle.SkyState.GradientSample;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.GsonUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static rs117.hd.utils.MathUtils.*;

/**
 * Run previewMigration as a JUnit test for a report without writes. Run main from the IDE to write
 * build/environments.sky-fog.json for comparison with the source file. Neither modifies the source.
 *
 * This is an offline tuning aid, not an exact rendering match: uniform fog also obscures the daytime
 * moon and removes the old angular variation. Twilight changes intentionally; opacity clears
 * gradually, but skyVisibility < 1 still blends in the night horizon tint afterward. The fit
 * excludes disks, localized glows, lightning and seasonal overrides.
 */
public class SkyFogMigrationTest {
	private static final Path SCENE = Path.of("src/main/resources/rs117/hd/scene");
	// Standalone test utility: no plugin instance or client-dependent adapters are needed.
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static class FogInput {
		@JsonAdapter(ColorUtils.SrgbToLinearAdapter.class)
		float[] fogColor;
	}

	public static void main(String[] args) throws IOException {
		Path output = Path.of("build/environments.sky-fog.json");
		JsonArray environments = migrate();
		Files.createDirectories(output.getParent());
		Files.writeString(output, GSON.toJson(environments) + "\n");
		System.out.println("Wrote " + output.toAbsolutePath());
	}

	@Test
	public void previewMigration() throws IOException {
		migrate();
	}

	@Test
	public void referenceDensities() {
		// Hand-tuned WILDERNESS_MID and post-quest VAMPYRIUM, respectively.
		assertEquals(3.2f, equivalentDensity(.05f, 0, 0), .15f);
		assertEquals(5.6f, equivalentDensity(.04f, 25, -30), .15f);
	}

	/**
	 * Report-only comparison of 9de9c73459's fog equations with the working tree's equations,
	 * on current definitions that did not use sunStrength there. Assumes OPACITY = 0.
	 * This isolates fog, not the full rendered image, and does not determine area/cycle eligibility.
	 */
	@Test
	public void reportUnmigratedFogChanges() throws Exception {
		var process = new ProcessBuilder("git", "show", "9de9c73459:src/main/resources/rs117/hd/scene/environments.json")
			.redirectError(ProcessBuilder.Redirect.INHERIT).start();
		JsonArray baseline;
		try (var reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
			baseline = GSON.fromJson(reader, JsonArray.class);
		}
		assertEquals("Unable to read baseline environments", 0, process.waitFor());
		var presets = loadPresets();
		JsonArray current = read("environments.json");
		assertEquals("Environment order must match baseline for this report", baseline.size(), current.size());
		var impacts = new ArrayList<FogImpact>();
		for (int i = 0; i < current.size(); i++) {
			JsonObject env = current.get(i).getAsJsonObject();
			JsonObject old = baseline.get(i).getAsJsonObject();
			for (String key : new String[] { "area", "key", "description", "varbitCondition", "varpCondition" })
				assertEquals("Environment order differs at " + i, old.get(key), env.get(key));
			JsonObject originalSky = resolveSky(old, presets);
			if (originalSky.has("sunStrength"))
				continue;
			SkyConfiguration sky = GSON.fromJson(resolveSky(env, presets), SkyConfiguration.class);
			float density = max(0, sky.skyFogDensity < 0 ? number(env, "fogDepth", 25) / 100 : sky.skyFogDensity);
			var impact = new FogImpact();
			impact.label = i + ": " + env.get("area") +
				(env.has("description") ? " " + env.get("description") : "");
			impact.density = density;
			impact.fixed = sky.sunAngles != null;
			// Sample twilight at one-degree intervals, or only the authored fixed sun altitude.
			for (int step = 0; step <= (impact.fixed ? 0 : 35); step++) {
				float altitude = impact.fixed ? sky.sunAngles[0] * RAD_TO_DEG : step - 30;
				float sunUp = sin(altitude * DEG_TO_RAD);
				float oldFade = smoothstep(sin(-18 * DEG_TO_RAD), 0, sunUp);
				float newFade = smoothstep(sin(-25 * DEG_TO_RAD), 0, sunUp);
				for (float viewAltitude : new float[] { -30, -10, 0, 10, 30, 60, 90 }) {
					float depth = density * pathLength(sin(viewAltitude * DEG_TO_RAD), sky.skyFogHorizonAltitude);
					float oldT = saturate(sky.skyVisibility) * exp(-depth * oldFade);
					float newT = saturate(sky.skyVisibility) * mix(1, exp(-depth), newFade);
					float difference = newT - oldT;
					if (abs(difference) > abs(impact.transmissionChange)) {
						impact.transmissionChange = difference;
						impact.sunAltitude = altitude;
						impact.viewAltitude = viewAltitude;
					}
					// Color change remains relevant even where density has fully cleared.
					impact.tintChanges |= sky.skyFogColor != null && altitude < 5 && newT < 1;
				}
			}
			if (abs(impact.transmissionChange) > .001f || impact.tintChanges)
				impacts.add(impact);
		}
		impacts.sort(Comparator.comparingDouble((FogImpact impact) -> abs(impact.transmissionChange)).reversed());
		System.out.println("Fog-only changes versus 9de9c73459 equations; + transmission means clearer, - means hazier.");
		System.out.println("All entries are conditional on an active sky cycle; interiors may never exercise these paths.");
		for (var impact : impacts)
			System.out.printf(java.util.Locale.ROOT,
				"%+.2f percentage points; density %.3f; sun %.1f, view %.1f degrees; %s%s%s%n",
				100 * impact.transmissionChange, impact.density, impact.sunAltitude, impact.viewAltitude,
				impact.label, impact.fixed ? " [fixed sun]" : "",
				impact.tintChanges ? " [authored fog also changes tint; not included in transmission ranking]" : "");
	}

	private static class FogImpact {
		String label;
		float density, transmissionChange, sunAltitude, viewAltitude;
		boolean fixed, tintChanges;
	}

	private static JsonObject resolveSky(JsonObject env, Map<String, JsonObject> presets) {
		var value = env.get("sky");
		JsonObject sky = value != null && value.isJsonObject() ? value.getAsJsonObject().deepCopy() : new JsonObject();
		String parent = value != null && value.isJsonPrimitive() ? value.getAsString() :
			sky.has("parent") ? sky.get("parent").getAsString() : "GIELINOR";
		assertNotNull("Unknown preset " + parent, presets.get(parent));
		GsonUtils.deepInheritFrom(sky, presets.get(parent));
		return sky;
	}

	private static JsonArray read(String file) throws IOException {
		try (var reader = Files.newBufferedReader(SCENE.resolve(file))) {
			return GSON.fromJson(reader, JsonArray.class);
		}
	}

	private static Map<String, JsonObject> loadPresets() throws IOException {
		var definitions = new LinkedHashMap<String, JsonObject>();
		for (var element : read("sky_presets.json")) {
			JsonObject preset = element.getAsJsonObject();
			definitions.put(preset.get("name").getAsString(), preset);
		}
		return GsonUtils.resolveParentDefinitions(definitions, "sky preset", null, GsonUtils::deepInheritFrom);
	}

	private static JsonArray migrate() throws IOException {
		var presets = loadPresets();
		JsonArray environments = read("environments.json");
		int changed = 0;
		int nightTintWarnings = 0;
		for (int index = 0; index < environments.size(); index++) {
			JsonObject env = environments.get(index).getAsJsonObject();
			if (!env.has("sky") || !env.get("sky").isJsonObject())
				continue;
			JsonObject sky = env.getAsJsonObject("sky");
			if (!sky.has("sunStrength"))
				continue;
			float strength = sky.get("sunStrength").getAsFloat();
			String label = index + ": " + env.get("area");
			if (!(strength > 0 && strength <= 1)) {
				System.out.println("REVIEW " + label + ": sunStrength=" + strength +
					" cannot be replaced by finite fog without changing its intent");
				continue;
			}

			JsonObject resolved = sky.deepCopy();
			String parent = sky.has("parent") ? sky.get("parent").getAsString() : "GIELINOR";
			assertNotNull("Unknown preset " + parent, presets.get(parent));
			GsonUtils.deepInheritFrom(resolved, presets.get(parent));
			// Deliberately use reflective parsing, without the client-dependent SkyConfiguration.Adapter.
			SkyConfiguration before = GSON.fromJson(resolved, SkyConfiguration.class);
			before.normalize();
			if (strength == 1 || before.customGradient || before.sunAngles != null && before.sunAngles[0] <= -25 * DEG_TO_RAD) {
				sky.remove("sunStrength");
				changed++;
				System.out.println("REMOVE redundant sunStrength " + label);
				continue;
			}

			float[] fogColor = GSON.fromJson(env, FogInput.class).fogColor;
			if (fogColor == null) {
				System.out.println("REVIEW " + label + ": fog color depends on runtime defaults; left unchanged");
				continue;
			}
			float density = before.skyFogDensity < 0 ? number(env, "fogDepth", 25) / 100 : before.skyFogDensity;
			float replacementDensity = equivalentDensity(strength, max(0, density), before.skyFogHorizonAltitude);
			resolved.addProperty("sunStrength", 1);
			SkyConfiguration after = GSON.fromJson(resolved, SkyConfiguration.class);
			after.normalize();
			float[] color = fitFogColor(before, after, fogColor, density, replacementDensity);
			sky.remove("sunStrength");
			changed++;
			sky.addProperty("skyFogDensity", replacementDensity);
			sky.addProperty("skyFogHorizonAltitude", 90);
			sky.addProperty("skyFogColor", ColorUtils.rgbToHex(color));
			System.out.printf(java.util.Locale.ROOT,
				"MIGRATE %s: sunStrength %.3f, density %.3f -> %.2f, fog %s%n",
				label, strength, density, replacementDensity, ColorUtils.rgbToHex(color));
			float nightFogWeight = 1 - saturate(before.skyVisibility);
			if (nightFogWeight > 0) {
				nightTintWarnings++;
				System.out.printf(java.util.Locale.ROOT,
					"NIGHT FOG REVIEW %s: skyVisibility %.3f leaves %.1f%% fog at night; " +
						"this follows the evaluated horizon color, not the fitted daytime fog color. " +
						"Review the tint transition through twilight.%n",
					label, saturate(before.skyVisibility), nightFogWeight * 100);
			}
		}
		System.out.println("Migrated " + changed + " environments, with " + nightTintWarnings +
			" nighttime fog tint warnings; review fixed-time scenes and twilight in game.");
		return environments;
	}

	private static float number(JsonObject json, String name, float fallback) {
		return json.has(name) ? json.get(name).getAsFloat() : fallback;
	}

	private static float pathLength(float up, float horizonAltitude) {
		float elevation = max(up - sin(clamp(horizonAltitude, -90, 90) * DEG_TO_RAD), 0) / .16f;
		return 1 / (1 + elevation * elevation);
	}

	private static float equivalentDensity(float strength, float density, float horizonAltitude) {
		// T_old * strength = T_new. Match the horizon rather than preserving a very large
		// density authored for a narrow band below it. A small artistic margin lies between
		// the ~0.05 and ~0.20 extra optical depths of the two hand-tuned references.
		float result = density * pathLength(0, horizonAltitude) - (float) Math.log(strength) + .1f;
		return Math.round(result * 100) / 100f;
	}

	private static float[] fitFogColor(
		SkyConfiguration before, SkyConfiguration after, float[] fogColor, float density, float replacementDensity
	) {
		float[] sunAltitudes = { 5, 15, 30, 60 };
		if (before.sunAngles != null && before.sunAngles[0] >= 0)
			sunAltitudes = new float[] { before.sunAngles[0] * RAD_TO_DEG };
		float[] viewAltitudes = { 0, 10, 30, 60 };
		float[] color = new float[3];
		float weight = 0;
		float visibility = saturate(before.skyVisibility);
		float newT = visibility * exp(-replacementDensity);
		var oldSample = new GradientSample();
		var newSample = new GradientSample();
		for (float sunAltitude : sunAltitudes) {
			before.evaluateGradient(oldSample, sunAltitude, fogColor);
			after.evaluateGradient(newSample, sunAltitude, fogColor);
			for (float viewAltitude : viewAltitudes) {
				float up = sin(viewAltitude * DEG_TO_RAD);
				float oldT = visibility * exp(-max(0, density) * pathLength(up, before.skyFogHorizonAltitude));
				for (int side = 0; side < 2; side++) {
					for (int c = 0; c < 3; c++) {
						float oldFog = before.skyFogColor == null ? oldSample.horizon[c] : before.skyFogColor[c];
						float target = mix(oldFog, gradient(oldSample, sunAltitude, up, side, c), oldT);
						// Least-squares fit of C_old = T_new * gradient_new + (1 - T_new) * fog_new.
						color[c] += (1 - newT) * (target - newT * gradient(newSample, sunAltitude, up, side, c));
					}
					weight += (1 - newT) * (1 - newT);
				}
			}
		}
		for (int c = 0; c < 3; c++)
			color[c] = saturate(color[c] / weight);
		return color;
	}

	// Daytime broad gradient from computeSkyGradient; intentionally excludes localized sun glow.
	private static float gradient(GradientSample sample, float sunAltitude, float up, int sunSide, int c) {
		float sunUp = sin(sunAltitude * DEG_TO_RAD);
		float darkSide = mix(sample.zenith[c] * mix(.7f, 1, smoothstep(0, .34f, sunUp)),
			sample.horizon[c], smoothstep(0, .64f, sunUp));
		float horizon = mix(darkSide, sample.horizon[c], sunSide);
		return mix(horizon, sample.zenith[c], smoothstep(-.1f, .7f, up));
	}
}
