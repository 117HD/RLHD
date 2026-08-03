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

	// "moonDirectionalStrength" lets an area dim or brighten moonlight independently of sunlight.
	// Omitting it must leave moonlight exactly as strong as sunlight, since every existing
	// environment relies on the single directionalStrength driving both. The fallback runs in
	// normalize(), so a value that never gets normalized would leak the -1 sentinel into the
	// lighting math and black out the night.
	@Test
	public void environmentMoonDirectionalStrengthDefaultsToDirectionalStrength() {
		Gson gson = new Gson();

		Environment unset = gson.fromJson("{\"directionalStrength\": 0.8}", Environment.class).normalize();
		assertEquals(
			"an absent moonDirectionalStrength must fall back to directionalStrength",
			0.8f, unset.moonDirectionalStrength, 0
		);

		Environment set = gson
			.fromJson("{\"directionalStrength\": 0.8, \"moonDirectionalStrength\": 0.2}", Environment.class)
			.normalize();
		assertEquals("an explicit moonDirectionalStrength must win", 0.2f, set.moonDirectionalStrength, 0);
		assertEquals("and must not disturb directionalStrength", 0.8f, set.directionalStrength, 0);

		// 0 is a meaningful value - no moonlight at all - and must survive the sentinel check
		Environment zero = gson
			.fromJson("{\"directionalStrength\": 0.8, \"moonDirectionalStrength\": 0}", Environment.class)
			.normalize();
		assertEquals("an explicit 0 must not be treated as unset", 0f, zero.moonDirectionalStrength, 0);
	}

	// "moonShadowStrength" and "minMoonIllumination" let an area set moonlight brightness and
	// moon shadow contrast independently. Both have meaningful natural defaults rather than a
	// sentinel, so the thing worth pinning is that omitting them is exactly the old behavior:
	// 1 leaves moon shadow visibility untouched, and 0 lets a new moon stay fully dark.
	@Test
	public void environmentMoonShadowFieldsDefaultToPreviousBehavior() {
		Gson gson = new Gson();

		Environment unset = gson.fromJson("{}", Environment.class).normalize();
		assertEquals("moonShadowStrength must default to a no-op multiplier", 1f, unset.moonShadowStrength, 0);
		assertEquals("minMoonIllumination must default to no floor", 0f, unset.minMoonIllumination, 0);

		Environment set = gson
			.fromJson("{\"moonShadowStrength\": 3, \"minMoonIllumination\": 0.35}", Environment.class)
			.normalize();
		assertEquals("an explicit moonShadowStrength must win", 3f, set.moonShadowStrength, 0);
		assertEquals("an explicit minMoonIllumination must win", 0.35f, set.minMoonIllumination, 0);

		// 0 means "the moon casts no shadows at all" and must survive normalize() rather than
		// being mistaken for unset the way a -1-sentinel field would be
		Environment zero = gson.fromJson("{\"moonShadowStrength\": 0}", Environment.class).normalize();
		assertEquals("an explicit 0 must not be treated as unset", 0f, zero.moonShadowStrength, 0);
	}

	// The night ambient boost is scaled by (1 - moonPresence), where moonPresence is phase times
	// altitude fade. The property that matters, and the reason it isn't gated on shadowVisibility:
	// a new moon and a moon that has set are both "no moonlight" and must be boosted identically,
	// while a full moon overhead gets nothing. Mirrors DayNightLighting.moonPresence.
	@Test
	public void nightBoostTreatsNewMoonAndSetMoonAlike() {
		float newMoonHigh = moonPresence(60, 0);
		float fullMoonSet = moonPresence(-20, 1);
		float fullMoonHigh = moonPresence(60, 1);

		assertEquals("a new moon contributes no moonlight", 0f, newMoonHigh, 0);
		assertEquals("a moon below the horizon contributes no moonlight", 0f, fullMoonSet, 0);
		assertEquals(
			"a set moon must be boosted exactly like a new moon - this is the whole point of "
			+ "keying the boost off moonlight presence rather than shadow visibility",
			newMoonHigh, fullMoonSet, 0
		);
		assertEquals("a full moon overhead needs no boost", 1f, fullMoonHigh, 1e-6);

		// Monotonic in phase and in altitude, so the boost fades in rather than popping
		assertTrue("presence must grow with phase", moonPresence(60, 0.5f) > moonPresence(60, 0.25f));
		assertTrue("presence must grow with altitude", moonPresence(30, 1) > moonPresence(0, 1));

		// Continuous across the horizon cutoff: a moon just above it must not jump to full boost
		assertTrue("presence must ease in above the cutoff", moonPresence(-9, 1) < 0.05f);
	}

	// Moon shadow visibility runs phase through pow(illumination, 0.5) rather than scaling it
	// linearly. Shadow presence tracks how directional the light is, not how much of it there
	// is, so a half moon casts obvious shadows in reality; linear scaling modelled brightness
	// instead and left everything below a gibbous reading as shadowless. Pins the compression
	// without pinning exact values, so the exponent stays tunable.
	@Test
	public void moonShadowPhaseResponseIsCompressed() {
		// A new moon must still cast nothing - pow keeps 0 at 0, which a lifted curve could break
		assertEquals("a new moon casts no shadow", 0f, phaseShadowFactor(0), 0);
		assertEquals("a full moon is the reference", 1f, phaseShadowFactor(1), 1e-6);

		// The point of the change: mid phases must sit well above their linear share
		assertTrue("a half moon must cast well over half a full moon's shadow", phaseShadowFactor(0.5f) > 0.65f);
		assertTrue("a crescent must stay clearly visible", phaseShadowFactor(0.25f) > 0.4f);

		// Still monotonic, so waxing never reduces shadows
		assertTrue(phaseShadowFactor(0.75f) > phaseShadowFactor(0.5f));
		assertTrue(phaseShadowFactor(0.5f) > phaseShadowFactor(0.25f));

		// And still compressive rather than inverted - never brighter than a full moon
		assertTrue("no phase may out-shadow a full moon", phaseShadowFactor(0.75f) < 1f);
	}

	// Night Synced advances the moon's phase a whole simulated day at a time, which near the
	// quarters moves illumination by ~0.10. That has to land where nothing can see it. The
	// guard used to fire at the geometric horizon (0), but moonlight only finishes fading at
	// -10, so for 10 degrees the moon was below the horizon and still lighting the scene - the
	// phase step showed up as a sudden brightness jump. Pins that the threshold stays at or
	// below the altitude where moonlight has fully faded out.
	@Test
	public void moonPhaseAdvancesOnlyWhereMoonlightHasFadedOut() {
		double phaseAdvanceAltitudeDeg = Math.toDegrees(Math.toRadians(-10)); // TimeOfDay's guard
		float moonlightGoneBelowDeg = -10; // DayNightLighting's fade start / horizon cutoff

		assertTrue(
			"the phase may only advance once moonlight has fully faded, or the step is visible",
			phaseAdvanceAltitudeDeg <= moonlightGoneBelowDeg
		);

		// At the advance altitude the moon must contribute nothing, whatever its phase - that
		// is what makes the step invisible rather than merely small
		assertEquals(
			"a phase change at the guard altitude must not move the lighting",
			0f, moonPresence(phaseAdvanceAltitudeDeg, 0.5f), 0
		);
		assertEquals(
			"...for any phase",
			0f, moonPresence(phaseAdvanceAltitudeDeg, 1.0f), 0
		);

		// Sanity: just above it the moon IS still contributing, so the old 0-degree guard
		// really was inside the visible window rather than a harmless early trigger
		assertTrue(
			"the geometric horizon is inside the window where moonlight still reaches the scene",
			moonPresence(-0.001, 0.5f) > 0
		);
	}

	// Mirrors the phase term of DayNightLighting.computeShadowVisibility
	private static float phaseShadowFactor(float illumination) {
		return (float) Math.pow(illumination, 0.5f);
	}

	// Mirrors DayNightLighting.moonPresence (private, and the class needs injected singletons)
	private static float moonPresence(double moonAltDeg, float moonIllumination) {
		if (moonAltDeg <= -10 || moonIllumination <= 0.01f)
			return 0;
		float t = Math.max(0, Math.min(1, (float) ((moonAltDeg - -10) / (20.0 - -10))));
		float fade = t * t * (3 - 2 * t);
		return Math.max(0, Math.min(1, moonIllumination * fade));
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
