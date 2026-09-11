package rs117.hd.tests;

import java.lang.reflect.Field;
import org.junit.Test;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.SkyManager;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class SkyManagerTest {
	@Test
	public void sunAnglesAreResolvedOncePerUpdate() throws ReflectiveOperationException {
		SkyManager skyManager = new SkyManager();
		EnvironmentManager environmentManager = new EnvironmentManager();
		setInjectedField(skyManager, "plugin", new HdPlugin());
		setInjectedField(skyManager, "environmentManager", environmentManager);
		setInjectedField(skyManager, "gielinorSky", new SkyConfiguration());
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
