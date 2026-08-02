package rs117.hd.tests;

import com.google.gson.Gson;
import org.junit.Test;
import rs117.hd.config.MoonPhase;
import rs117.hd.renderer.zone.DayNightLighting;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.environments.Environment;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the two bugs found while extracting DayNightLighting out of ZoneRenderer.
 * Both were invisible to the compiler and produced wrong rendering rather than an error.
 */
public class DayNightLightingTest {
	// DayNightLighting.Lighting must own its color arrays. It used to be seeded by assigning
	// EnvironmentManager's arrays directly, so the cycle's in-place moon tint wrote straight
	// into the environment's state. It was safe only because the one mutating path happened to
	// be handed a fresh array first - a coincidence, not a guarantee.
	@Test
	public void lightingMustNotAliasEnvironmentColors() {
		EnvironmentManager env = new EnvironmentManager();
		env.currentDirectionalColor = new float[] { .1f, .2f, .3f };
		env.currentAmbientColor = new float[] { .4f, .5f, .6f };
		env.currentWaterColor = new float[] { .7f, .8f, .9f };
		env.currentFogColor = new float[] { .15f, .25f, .35f };
		env.currentDirectionalStrength = 2.5f;
		env.currentAmbientStrength = 1.5f;

		var lighting = new DayNightLighting.Lighting();
		lighting.seedFrom(env);

		assertNotSame("directionalColor must be a copy", env.currentDirectionalColor, lighting.directionalColor);
		assertNotSame("ambientColor must be a copy", env.currentAmbientColor, lighting.ambientColor);
		assertNotSame("waterColor must be a copy", env.currentWaterColor, lighting.waterColor);

		// The values must still round-trip, or seeding silently drops the environment's lighting
		assertArrayEquals("directionalColor must be seeded", new float[] { .1f, .2f, .3f }, lighting.directionalColor, 0);
		assertArrayEquals("ambientColor must be seeded", new float[] { .4f, .5f, .6f }, lighting.ambientColor, 0);
		assertArrayEquals("waterColor must be seeded", new float[] { .7f, .8f, .9f }, lighting.waterColor, 0);
		assertEquals("directionalStrength must be seeded", 2.5f, lighting.directionalStrength, 0);
		assertEquals("ambientStrength must be seeded", 1.5f, lighting.ambientStrength, 0);

		// Simulate the cycle's in-place moon tint and sky-fill ambient
		lighting.directionalColor[0] = 0.999f;
		lighting.ambientColor[0] = 0.999f;
		lighting.waterColor[0] = 0.999f;

		assertArrayEquals(
			"Mutating lighting must not corrupt the environment",
			new float[] { .1f, .2f, .3f }, env.currentDirectionalColor, 0
		);
		assertArrayEquals(
			"Mutating lighting must not corrupt the environment",
			new float[] { .4f, .5f, .6f }, env.currentAmbientColor, 0
		);
		assertArrayEquals(
			"Mutating lighting must not corrupt the environment",
			new float[] { .7f, .8f, .9f }, env.currentWaterColor, 0
		);
	}

	// Pins the overload-resolution rule behind the nebula bug, without needing a GL context
	// (UniformBuffer.initialize allocates a real GL buffer, so Property can't be exercised here).
	//
	// Writing a Float uniform with an all-int expression - `prop.set(cond ? 1 : 0)` - binds
	// set(int) rather than set(float), because int->int is an exact match and beats int->float
	// widening. UniformBuffer's int setter then sees a Float property, skips the write and
	// returns, so the uniform silently keeps its old value. That shipped once as nebulas never
	// turning on. UniformBuffer now asserts on the mismatch; this test guards the language
	// behavior that makes the mistake so easy to write in the first place.
	@Test
	public void allIntTernaryBindsTheIntOverload() {
		boolean enabled = true;

		assertEquals(
			"An all-int ternary is an int expression, so `set(cond ? 1 : 0)` on a Float uniform "
			+ "binds set(int) and silently no-ops - always write `1f : 0f`",
			"int", overloadPickedFor(enabled ? 1 : 0)
		);

		// Adding the f suffixes is what routes it to the float setter
		assertEquals("float", overloadPickedFor(enabled ? 1f : 0f));

		// A float on either branch also promotes the whole expression, which is why the
		// neighbouring `... ? env.currentMoonVisibility : 0` calls were never affected
		float someFloat = 0.5f;
		assertEquals("float", overloadPickedFor(enabled ? someFloat : 0));
	}

	private static String overloadPickedFor(int value) {
		return "int";
	}

	private static String overloadPickedFor(float value) {
		return "float";
	}

	// Environments can force a moon phase via a "forceMoonPhase" field, using the same names as
	// the config dropdown. Gson matches enum constants by exact name and silently yields null
	// for anything it doesn't recognise - including an unknown field name - so a typo or a
	// renamed constant would quietly fall back to the configured phase rather than failing.
	// Pin every value the dropdown offers, and the field name itself.
	@Test
	public void environmentForceMoonPhaseParsesEveryConfigValue() {
		Gson gson = new Gson();

		for (MoonPhase phase : MoonPhase.values()) {
			Environment env = gson.fromJson("{\"forceMoonPhase\": \"" + phase.name() + "\"}", Environment.class);
			assertEquals("every config dropdown value must parse", phase, env.forceMoonPhase);
		}

		// Omitting the field means "defer to the player's config", so it must stay null
		assertNull("an absent forceMoonPhase must not force anything", gson.fromJson("{}", Environment.class).forceMoonPhase);

		// The old name must no longer bind, or renamed JSON would silently stop taking effect
		assertNull(
			"the pre-rename \"moonPhase\" field must not bind",
			gson.fromJson("{\"moonPhase\": \"FULL_MOON\"}", Environment.class).forceMoonPhase
		);
	}

	// "forceMoonActive" lets a cutscene environment show the moon even when the player has the
	// Moon config toggle off. It must default to false, so ordinary environments keep deferring
	// to the config rather than silently forcing the moon on everywhere.
	@Test
	public void environmentForceMoonActiveParses() {
		Gson gson = new Gson();

		assertTrue(
			"forceMoonActive must parse",
			gson.fromJson("{\"forceMoonActive\": true}", Environment.class).forceMoonActive
		);
		assertFalse(
			"an explicit false must not force the moon",
			gson.fromJson("{\"forceMoonActive\": false}", Environment.class).forceMoonActive
		);
		assertFalse(
			"an absent forceMoonActive must default to deferring to the config",
			gson.fromJson("{}", Environment.class).forceMoonActive
		);
	}
}
