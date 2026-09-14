package rs117.hd.tests;

import org.junit.Test;
import rs117.hd.scene.lights.Light;
import rs117.hd.scene.lights.LightDefinition;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;

public class LightColorAliasingTest {
	@Test
	public void lightColorMustNotAliasDefinitionColor() {
		LightDefinition def = new LightDefinition();
		def.color = new float[] { .25f, .5f, .75f, 1 };

		Light light = new Light(def);
		assertNotSame("Light.color must be a copy, not an alias", def.color, light.color);

		// Simulate what applyTimeOfDayColor does every frame
		light.color[0] = 0.999f;
		assertArrayEquals(
			"Mutating Light.color must not corrupt the shared definition",
			new float[] { .25f, .5f, .75f, 1 }, def.color, 0f
		);
	}
}
