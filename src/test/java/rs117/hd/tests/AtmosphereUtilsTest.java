package rs117.hd.tests;

import org.junit.Test;
import rs117.hd.scene.TimeOfDay;
import rs117.hd.utils.AtmosphereUtils;

import static org.junit.Assert.assertArrayEquals;

/**
 * Characterization tests locking in the output of the atmosphere color functions.
 * Golden values were captured from the implementation prior to pre-linearizing
 * the constant keyframe tables; any drift beyond 1e-6 indicates a behavior change.
 */
public class AtmosphereUtilsTest {
	private static double[] angles(double altitudeDegrees) {
		return new double[] { 0, Math.toRadians(altitudeDegrees) };
	}

	@Test
	public void ambientColorMatchesGolden() {
		assertArrayEquals(
			new float[] { 0.165132225f, 0.262250721f, 0.456411064f },
			AtmosphereUtils.getAmbientColorForAngles(angles(-8)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.225462720f, 0.299400598f, 0.547009230f },
			AtmosphereUtils.getAmbientColorForAngles(angles(0)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.370255947f, 0.388560295f, 0.764444828f },
			AtmosphereUtils.getAmbientColorForAngles(angles(12)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.513126791f, 0.547581077f, 1.000000000f },
			AtmosphereUtils.getAmbientColorForAngles(angles(30)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			AtmosphereUtils.getAmbientColorForAngles(angles(60)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.485149980f, 0.672443211f, 1.000000000f },
			AtmosphereUtils.getAmbientColorForAngles(angles(85)), 1e-6f
		);
	}

	@Test
	public void skyColorMatchesGolden() {
		assertArrayEquals(
			new float[] { 0.156218588f, 0.156218588f, 0.213644534f },
			AtmosphereUtils.getSkyColorForAngles(angles(-8)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.337469906f, 0.337469906f, 0.449223995f },
			AtmosphereUtils.getSkyColorForAngles(angles(0)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.486051321f, 0.486051321f, 0.641196847f },
			AtmosphereUtils.getSkyColorForAngles(angles(12)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.645360351f, 0.691880226f, 0.866488039f },
			AtmosphereUtils.getSkyColorForAngles(angles(30)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.725490212f, 0.839215696f, 0.999999940f },
			AtmosphereUtils.getSkyColorForAngles(angles(60)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.725490212f, 0.839215696f, 0.999999940f },
			AtmosphereUtils.getSkyColorForAngles(angles(85)), 1e-6f
		);
	}

	@Test
	public void directionalLightMatchesGolden() {
		TimeOfDay tod = new TimeOfDay();
		assertArrayEquals(
			new float[] { 0.116922617f, 0.096754856f, 0.066591375f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(-8)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.116922617f, 0.096754856f, 0.066591375f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(0)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 0.302544773f, 0.215248346f, 0.120878309f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(12)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 1.009382844f, 0.819268167f, 0.548119307f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(30)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 2.343648672f, 2.354422808f, 2.118747473f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(60)), 1e-6f
		);
		assertArrayEquals(
			new float[] { 2.864768744f, 3.122953415f, 3.115890741f },
			AtmosphereUtils.getDirectionalLightForAngles(tod, angles(85)), 1e-6f
		);
	}
}
