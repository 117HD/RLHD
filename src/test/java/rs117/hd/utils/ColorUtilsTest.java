package rs117.hd.utils;

import java.util.Arrays;
import org.junit.Test;

import static junit.framework.TestCase.assertEquals;
import static rs117.hd.utils.ColorUtils.linearToSrgb;
import static rs117.hd.utils.ColorUtils.packHsl;
import static rs117.hd.utils.ColorUtils.srgbToLinear;
import static rs117.hd.utils.ColorUtils.srgbToPackedHsl;
import static rs117.hd.utils.ColorUtils.unpackHsl;

public class ColorUtilsTest {
	@Test
	public void testJagexHslPacking() {
		float[] hsl;
		for (int counter = 0; (counter & ~0xFFFF) == 0; counter++) {
			int packedHslBefore = counter;
			hsl = unpackHsl(packedHslBefore);
			// Zero saturation or min/max lightness yield the same color
			if (hsl[1] <= .0625f || hsl[2] == 0 || hsl[2] >= 127f / 128) {
				hsl[0] = .0078125f;
				hsl[1] = .0625f;
				packedHslBefore = packHsl(hsl);
			}

			float[] srgbBefore = ColorUtils.packedHslToSrgb(packedHslBefore);
			float[] srgbAfter = linearToSrgb(srgbToLinear(srgbBefore));

			int packedHslAfter = srgbToPackedHsl(srgbAfter);
			if (packedHslBefore != packedHslAfter) {
				assertEquals(String.format(
					"Inaccurate color, packedHsl: %d\t->\t%d,\tHSL: %s\t->\t%s,\tRGB: %s\t->\t%s\n",
					packedHslBefore,
					packedHslAfter,
					Arrays.toString(hsl),
					Arrays.toString(unpackHsl(packedHslAfter)),
					Arrays.toString(srgbBefore),
					Arrays.toString(srgbAfter)
				), packedHslBefore, packedHslAfter);
			}
		}
	}
}
