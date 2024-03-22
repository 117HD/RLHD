package rs117.hd.utils;

import junit.framework.TestCase;

public class MatrixTest extends TestCase {
	public void testSolve() {
		float[] invertible = {
			0, 0, -1, 2,
			0, 1, 0, 0,
			9, 0, 0, 0,
			0, 0, 0, 1
		};
		System.out.println(Matrix.format(invertible, 4, 4));
		Matrix.solve(invertible, 4, 4);
		System.out.println(Matrix.format(invertible, 4, 4));

		float[] linearSystem = {
			-1382.59f, -1, 0, 0.11f,
			1180.23f, 0, 661.96f, 0.08f,
			661.96f, 0, -1180.23f, -0.11f
		};
		System.out.println(Matrix.format(linearSystem, 3, 4));
		Matrix.solve(linearSystem, 3, 4);
		System.out.println(Matrix.format(linearSystem, 3, 4));
	}
}
