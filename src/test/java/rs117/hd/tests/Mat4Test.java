package rs117.hd.tests;

import org.junit.Assert;
import org.junit.Test;
import rs117.hd.utils.Mat4;

public class Mat4Test {
	@Test
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

	@Test
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

		float[] invertible2 = {
			0.20491976f, -0.1548707f, 0.0f, 1.8616436E-8f,
			0.0f, -1.589505E-8f, 0.0f, -1.0f,
			0.096459776f, 0.32900834f, 0.0f, -3.954888E-8f,
			-1846.3291f, -1871.3298f, 100.0f, 3498.0002f
		};
		float[] inverse2 = Mat4.inverse(invertible2);
		Mat4.mul(inverse2, invertible2);
		Assert.assertEquals(identity, Mat4.format(inverse2));
	}
}
