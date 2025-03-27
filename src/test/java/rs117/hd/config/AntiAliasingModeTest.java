package rs117.hd.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AntiAliasingModeTest {

	@Test
	void testGetName() {
		assertEquals("Disabled", AntiAliasingMode.DISABLED.getName());
		assertEquals("MSAA x2", AntiAliasingMode.MSAA_2.getName());
		assertEquals("MSAA x4", AntiAliasingMode.MSAA_4.getName());
		assertEquals("MSAA x8", AntiAliasingMode.MSAA_8.getName());
		assertEquals("MSAA x16", AntiAliasingMode.MSAA_16.getName());
	}

	@Test
	void testGetSamples() {
		assertEquals(0, AntiAliasingMode.DISABLED.getSamples());
		assertEquals(2, AntiAliasingMode.MSAA_2.getSamples());
		assertEquals(4, AntiAliasingMode.MSAA_4.getSamples());
		assertEquals(8, AntiAliasingMode.MSAA_8.getSamples());
		assertEquals(16, AntiAliasingMode.MSAA_16.getSamples());
	}

	@Test
	void testToString() {
		assertEquals("Disabled", AntiAliasingMode.DISABLED.toString());
		assertEquals("MSAA x2", AntiAliasingMode.MSAA_2.toString());
		assertEquals("MSAA x4", AntiAliasingMode.MSAA_4.toString());
		assertEquals("MSAA x8", AntiAliasingMode.MSAA_8.toString());
		assertEquals("MSAA x16", AntiAliasingMode.MSAA_16.toString());
	}
}
