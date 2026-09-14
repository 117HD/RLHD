package rs117.hd.tests;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.Test;
import rs117.hd.config.MoonPhase;
import rs117.hd.renderer.SkyRenderer;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.scene.daylight_cycle.SkyConfiguration.SkyProfile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static rs117.hd.utils.MathUtils.*;

/**
 * Characterization tests locking in the sky renderer's procedural lighting colors.
 * Golden values were captured from the implementation prior to pre-linearizing
 * the constant keyframe tables; any drift beyond 1e-6 indicates a behavior change.
 */
public class SkyRendererTest {
	private static final SkyProfile SKY_PROFILE = loadSkyProfile();

	private static SkyProfile loadSkyProfile() {
		var resource = Objects.requireNonNull(SkyRendererTest.class.getResourceAsStream("/rs117/hd/scene/daylight_cycle/sky_presets.json"));
		SkyConfiguration[] presets = new Gson().fromJson(new InputStreamReader(resource, StandardCharsets.UTF_8), SkyConfiguration[].class);
		return presets[0].profile;
	}

	private static float[] getAmbientColor(float altitudeDegrees) {
		return SKY_PROFILE.getAmbientLight(altitudeDegrees);
	}

	private static float[] getDirectionalLight(float altitudeDegrees) {
		return SKY_PROFILE.getDirectionalLight(altitudeDegrees * DEG_TO_RAD);
	}

	@Test
	public void ambientColorMatchesGolden() {
		assertArrayEquals(
			new float[] { 0.165132225f, 0.262250721f, 0.456411064f },
			getAmbientColor(-8), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.225462720f, 0.299400598f, 0.547009230f },
			getAmbientColor(0), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.370255947f, 0.388560295f, 0.764444828f },
			getAmbientColor(12), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.513126791f, 0.547581077f, 1.000000000f },
			getAmbientColor(30), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			getAmbientColor(60), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			getAmbientColor(85), 1e-6f
		);
	}

	@Test
	public void directionalLightMatchesGolden() {
		assertArrayEquals(
			new float[] { .13896565f, .09286611f, .055899985f },
			getDirectionalLight(-8), 1e-6f
		);
		assertArrayEquals(
			new float[] { .13896565f, .09286611f, .055899985f },
			getDirectionalLight(0), 1e-6f
		);
		assertArrayEquals(
			new float[] { .36123133f, .20414308f, .09520696f },
			getDirectionalLight(12), 1e-6f
		);
		assertArrayEquals(
			new float[] { 1.1999731f, .78566045f, .45654672f },
			getDirectionalLight(30), 1e-6f
		);
		assertArrayEquals(
			new float[] { 2.790303f, 2.2654557f, 1.8906446f },
			getDirectionalLight(60), 1e-6f
		);
		assertArrayEquals(
			new float[] { 3.4223082f, 2.999315f, 2.832426f },
			getDirectionalLight(85), 1e-6f
		);
	}

	@Test
	public void allIntTernaryBindsTheIntOverload() {
		boolean enabled = true;
		assertEquals("int", overloadPickedFor(enabled ? 1 : 0));
		assertEquals("float", overloadPickedFor(enabled ? 1f : 0f));

		float value = .5f;
		assertEquals("float", overloadPickedFor(enabled ? value : 0));
	}

	private static String overloadPickedFor(int value) {
		return "int";
	}

	private static String overloadPickedFor(float value) {
		return "float";
	}

	@Test
	public void skyForceMoonPhaseParsesEveryConfigValue() {
		Gson gson = new Gson();
		MoonPhase[] phases = MoonPhase.values();
		for (int i = 0; i < phases.length; i++) {
			MoonPhase phase = phases[i];
			SkyConfiguration sky = gson.fromJson("{\"forceMoonPhase\": \"" + phase.name() + "\"}", SkyConfiguration.class);
			assertEquals(phase, sky.forceMoonPhase);
		}

		assertNull(gson.fromJson("{}", SkyConfiguration.class).forceMoonPhase);
		assertNull(gson.fromJson("{\"moonPhase\": \"FULL_MOON\"}", SkyConfiguration.class).forceMoonPhase);
	}

	@Test
	public void skyMoonDirectionalStrengthDefaultsToEnvironmentDirectionalStrength() {
		Gson gson = new Gson();
		SkyConfiguration unset = gson.fromJson("{}", SkyConfiguration.class);
		assertEquals(-1, unset.moonDirectionalStrength, 0);

		SkyConfiguration set = gson
			.fromJson("{\"moonDirectionalStrength\": 0.2}", SkyConfiguration.class);
		assertEquals(.2f, set.moonDirectionalStrength, 0);

		SkyConfiguration zero = gson
			.fromJson("{\"moonDirectionalStrength\": 0}", SkyConfiguration.class);
		assertEquals(0, zero.moonDirectionalStrength, 0);
	}

	@Test
	public void skyMoonShadowFieldsDefaultToPreviousBehavior() {
		Gson gson = new Gson();
		SkyConfiguration unset = gson.fromJson("{}", SkyConfiguration.class);
		assertEquals(1, unset.moonShadowStrength, 0);
		assertEquals(0, unset.minMoonIllumination, 0);

		SkyConfiguration set = gson
			.fromJson("{\"moonShadowStrength\": 3, \"minMoonIllumination\": 0.35}", SkyConfiguration.class);
		assertEquals(3, set.moonShadowStrength, 0);
		assertEquals(.35f, set.minMoonIllumination, 0);

		SkyConfiguration zero = gson.fromJson("{\"moonShadowStrength\": 0}", SkyConfiguration.class);
		assertEquals(0, zero.moonShadowStrength, 0);
	}

	@Test
	public void nightBoostTreatsNewMoonAndSetMoonAlike() {
		float newMoonHigh = moonPresence(60, 0);
		float fullMoonSet = moonPresence(-20, 1);
		float fullMoonHigh = moonPresence(60, 1);

		assertEquals(0, newMoonHigh, 0);
		assertEquals(0, fullMoonSet, 0);
		assertEquals(newMoonHigh, fullMoonSet, 0);
		assertEquals(1, fullMoonHigh, 1e-6);
		assertTrue(moonPresence(60, .5f) > moonPresence(60, .25f));
		assertTrue(moonPresence(30, 1) > moonPresence(0, 1));
		assertTrue(moonPresence(-9, 1) < .05f);
	}

	@Test
	public void fullMoonKeepsPartOfTheBrightnessBoost() {
		float newMoon = boostFraction(60, 0);
		float fullMoonHigh = boostFraction(60, 1);

		assertEquals(1, newMoon, 1e-6);
		assertEquals(.2f, fullMoonHigh, 1e-6);
		assertTrue(boostFraction(60, .25f) > fullMoonHigh);
		assertTrue(boostFraction(60, 1) < boostFraction(0, 1));
		assertEquals(newMoon, boostFraction(-20, 1), 0);
	}

	@Test
	public void shadowBlurPreservesAverageIrradianceWithUnequalColorsAndStrengths() throws Exception {
		float[] altitudes = { -5, 0, 5, 30, 90 };
		for (int i = 0; i < altitudes.length; i++) {
			SkyRenderer renderer = new SkyRenderer();
			setLightingField(renderer, "ambientColor", new float[] { .2f, 1.5f, .7f });
			setLightingField(renderer, "directionalColor", new float[] { 2, .3f, 4 });
			setLightingField(renderer, "ambientStrength", .7f);
			setLightingField(renderer, "directionalStrength", 4f);
			float[] before = averageIrradiance(renderer);
			var blur = SkyRenderer.class.getDeclaredMethod("applyShadowBlur", float.class, float.class, float.class);
			blur.setAccessible(true);
			blur.invoke(renderer, altitudes[i], .533f, 1f);
			assertArrayEquals(before, averageIrradiance(renderer), 1e-6f);
		}
	}

	@Test
	public void shadowBlurTransfersLightIntoInitiallyZeroAmbient() throws Exception {
		SkyRenderer renderer = new SkyRenderer();
		setLightingField(renderer, "directionalColor", new float[] { 2, 1, .5f });
		setLightingField(renderer, "directionalStrength", 4f);
		var blur = SkyRenderer.class.getDeclaredMethod("applyShadowBlur", float.class, float.class, float.class);
		blur.setAccessible(true);
		blur.invoke(renderer, 0f, .533f, 1f);
		assertEquals(0, (float) getLightingField(renderer, "directionalStrength"), 0);
		assertArrayEquals(new float[] { 2, 1, .5f }, averageIrradiance(renderer), 1e-6f);
	}

	@Test
	public void moonPresenceIsZeroForNewOrSetMoon() {
		assertEquals(0, moonPresence(60, 0), 0);
		assertEquals(0, moonPresence(-10, .5f), 0);
		assertEquals(0, moonPresence(-10, 1), 0);
		assertTrue(moonPresence(-.001, .5f) > 0);
	}

	private static float[] averageIrradiance(SkyRenderer renderer) throws Exception {
		return add(
			multiply((float[]) getLightingField(renderer, "ambientColor"), (float) getLightingField(renderer, "ambientStrength")),
			multiply((float[]) getLightingField(renderer, "directionalColor"), (float) getLightingField(renderer, "directionalStrength") / 4)
		);
	}

	private static Object getLightingField(SkyRenderer renderer, String name) throws Exception {
		var field = SkyRenderer.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(renderer);
	}

	private static void setLightingField(SkyRenderer renderer, String name, Object value) throws Exception {
		var field = SkyRenderer.class.getDeclaredField(name);
		field.setAccessible(true);
		if (value instanceof float[])
			copyTo((float[]) field.get(renderer), (float[]) value);
		else
			field.set(renderer, value);
	}

	private static float moonPresence(double moonAltitudeDegrees, float moonIllumination) {
		if (moonAltitudeDegrees <= -10 || moonIllumination <= .01f)
			return 0;
		float t = saturate((float) ((moonAltitudeDegrees + 10) / 30));
		return saturate(moonIllumination * (t * t * (3 - 2 * t)));
	}

	private static float boostFraction(double moonAltitudeDegrees, float moonIllumination) {
		return .2f + .8f * (1 - moonPresence(moonAltitudeDegrees, moonIllumination));
	}
}
