package rs117.hd.utils;

import junit.framework.TestCase;
import org.junit.Assert;

public class Mat4Test extends TestCase {
	public void testTranspose() {
		float[] m = {
			1, 2, 3, 4,
			5, 6, 7, 8,
			9, 10, 11, 12,
			13, 14, 15, 16
		};
		System.out.println(Mat4.format(m));
		Mat4.transpose(m);
		System.out.println("\n" + Mat4.format(m));
	}

	public void testInverse() {
		float[] nonInvertible = {
			1, -2, 0, 2,
			-1, 3, 1, -2,
			-1, 5, 3, -2,
			0, 7, 7, 0
		};
		Mat4.transpose(nonInvertible);
		Assert.assertThrows(IllegalArgumentException.class, () -> Mat4.inverse(nonInvertible));

		String identity = Mat4.format(Mat4.identity());

		float[] invertible = {
			0, 0, -1, 2,
			0, 1, 0, 0,
			9, 0, 0, 0,
			0, 0, 0, 1
		};
		Mat4.transpose(invertible);
		float[] inverse = Mat4.inverse(invertible);
		Mat4.mul(inverse, invertible);
		Assert.assertEquals(identity, Mat4.format(inverse));

		invertible = new float[] {
			4, 0, 0, 0,
			0, 0, 2, 0,
			0, 1, 2, 0,
			1, 0, 0, 1
		};
		Mat4.transpose(invertible);
		inverse = Mat4.inverse(invertible);
		Mat4.mul(inverse, invertible);
		Assert.assertEquals(identity, Mat4.format(inverse));
	}
}
