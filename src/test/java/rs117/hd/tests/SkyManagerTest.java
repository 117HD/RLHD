package rs117.hd.tests;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.time.Instant;
import org.junit.Test;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.SkyManager;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.scene.daylight_cycle.SkyState.LightingSample;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.utils.AstronomyUtils;

import static org.junit.Assert.assertEquals;
import static rs117.hd.utils.MathUtils.*;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class SkyManagerTest {
	@Test
	public void samplingIgnoresCurrentAreaClockAndMoonOverrides() throws ReflectiveOperationException {
		SkyManager manager = new SkyManager();
		Instant frame = Instant.parse("2026-06-21T20:00:00Z");
		setInjectedField(manager, "frameUtcInstant", frame);
		setInjectedField(manager, "frameUtcMillis", frame.toEpochMilli());
		setInjectedField(manager, "configCycle", DaylightCycle.CUSTOM_REALISTIC);
		setInjectedField(manager, "customCycleElapsedDays", .75);
		setInjectedField(manager, "customCycleStart", Instant.parse("2026-06-21T00:00:00Z"));
		setInjectedField(manager, "configMoonBehavior", MoonBehavior.MIRRORED);
		setInjectedField(manager, "configMoonPhase", MoonPhase.FULL_MOON);
		manager.getState().moonVisibility = 0;
		manager.getState().moonIllumination = 0;
		manager.getState().moonLightIllumination = 0;
		manager.getState().moonAltitudeDegrees = -80;

		SkyConfiguration sky = loadDefaultPreset();
		LightingSample sample = new LightingSample();
		manager.sampleLighting(sample, sky, new float[] { 1, 1, 1 }, .7f);
		// The default test coordinates are zero. Custom's .75 day is 18:00 UTC, independent of the cave clock.
		float expectedAltitude = (float) AstronomyUtils.getSunAngles(
			Instant.parse("2026-06-21T18:00:00Z").toEpochMilli(), new double[2])[0] * RAD_TO_DEG;
		assertEquals(expectedAltitude, sample.state.sunAltitudeDegrees, 1e-5f);
		assertEquals(-expectedAltitude, sample.state.moonAltitudeDegrees, 1e-5f);
		assertEquals(MoonPhase.FULL_MOON.illumination, sample.state.moonLightIllumination, 0);

		sky.sunAngles = new float[] { -30 * DEG_TO_RAD, 0 };
		sky.moonAngles = new float[] { 20 * DEG_TO_RAD, 0 };
		sky.forceMoonPhase = MoonPhase.FIRST_QUARTER;
		sky.moonVisibility = .5f;
		sky.moonLightVisibility = .5f;
		manager.sampleLighting(sample, sky, new float[] { 1, 1, 1 }, .7f);
		assertEquals(-30, sample.state.sunAltitudeDegrees, 1e-5f);
		assertEquals(20, sample.state.moonAltitudeDegrees, 1e-5f);
		assertEquals(.25f, sample.state.moonLightIllumination, 0);
		sky.hideMoon = true;
		manager.sampleLighting(sample, sky, new float[] { 1, 1, 1 }, .7f);
		assertEquals(0, sample.state.moonLightIllumination, 0);
		sky.moonLightVisibility = 0;
		manager.sampleLighting(sample, sky, new float[] { 1, 1, 1 }, .7f);
		assertEquals(0, sample.state.moonLightIllumination, 0);
	}

	@Test
	public void sunAnglesAreResolvedOncePerUpdate() throws ReflectiveOperationException {
		SkyManager skyManager = new SkyManager();
		EnvironmentManager environmentManager = new EnvironmentManager();
		setInjectedField(skyManager, "plugin", new HdPlugin());
		setInjectedField(skyManager, "environmentManager", environmentManager);
		SkyConfiguration previousDefault = SkyConfiguration.DEFAULT_PRESET;
		SkyConfiguration.DEFAULT_PRESET = loadDefaultPreset();
		try {
			skyManager.updateConfig(new HdPluginConfig() {
				@Override
				public void setPluginUpdateMessage(int version) {}

				@Override
				public void tiledLighting(boolean enabled) {}
			});

			// update() is the per-frame entry point: it pins the instant and resolves
			// the complete celestial state before any consumer reads it.
			skyManager.update();
			float[] first = getSunAngles(skyManager);
			float[] second = getSunAngles(skyManager);
			assertSame("within one frame, consumers must share the resolved sun angles", first, second);

			skyManager.update();
			float[] third = getSunAngles(skyManager);
			assertNotSame("each update must resolve a fresh sun-angle array", first, third);
		} finally {
			SkyConfiguration.DEFAULT_PRESET = previousDefault;
		}
	}

	private static SkyConfiguration loadDefaultPreset() {
		var resource = Objects.requireNonNull(SkyManagerTest.class.getResourceAsStream("/rs117/hd/scene/daylight_cycle/sky_presets.json"));
		SkyConfiguration preset = new Gson().fromJson(new InputStreamReader(resource, StandardCharsets.UTF_8), SkyConfiguration[].class)[0];
		preset.normalize();
		return preset;
	}

	private static void setInjectedField(Object target, String name, Object value) throws ReflectiveOperationException {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static float[] getSunAngles(SkyManager skyManager) {
		return skyManager.getState().sunAngles;
	}
}
