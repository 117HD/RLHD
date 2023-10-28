package rs117.hd.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.lights.Light;

import static org.junit.Assert.assertEquals;
import static rs117.hd.utils.ResourcePath.path;

public class LightConfigTest {
	private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
	private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
	private final PrintStream originalStdout = System.out;
	private final PrintStream originalStderr = System.err;

	@Before
	public void setupStreams() {
		System.setOut(new PrintStream(stdout));
		System.setErr(new PrintStream(stderr));
	}

	@After
	public void restoreStreams() {
		System.setErr(originalStdout);
		System.setErr(originalStderr);
	}

	@Test
    public void testLoad() {
		Gson gson = new GsonBuilder().setLenient().create();
		LightManager lightManager = new LightManager();
		lightManager.loadConfig(gson, path(LightConfigTest.class, "lights.json"));

        // can we get the same light for both of its raw IDs?
        Light spitRoastLight = lightManager.OBJECT_LIGHTS.get(5608).get(0);
        assertEquals(spitRoastLight, lightManager.OBJECT_LIGHTS.get(4267).get(0));

        // is its data correct?
        assertEquals("SPIT_ROAST", spitRoastLight.description);
        assertEquals(50, spitRoastLight.height);
        assertEquals("CENTER", spitRoastLight.alignment.toString());
        assertEquals(250, spitRoastLight.radius);
        assertEquals(12.5, spitRoastLight.strength, 0.0);
        assertEquals("FLICKER", spitRoastLight.type.toString());
        assertEquals(0.0, spitRoastLight.duration, 0.0);
        assertEquals(20.0, spitRoastLight.range, 0.0);
        assertEquals(0.9743002, spitRoastLight.color[0], 0.001);
        assertEquals(0.29613828659057617, spitRoastLight.color[1], 0.001);
        assertEquals(5.6921755E-5, spitRoastLight.color[2], 0.001);
    }
}
