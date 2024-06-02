/*
 * Copyright (c) 2022 Abex
 * Copyright 2010 JogAmp Community.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.utils;

public class Mat4
{
	/**
	 * Utility class for working with column-major 4 x 4 matrices.
	 */

	public static float[] identity()
	{
		return new float[]
			{
				1, 0, 0, 0,
				0, 1, 0, 0,
				0, 0, 1, 0,
				0, 0, 0, 1,
			};
	}

	public static float[] scale(float sx, float sy, float sz)
	{
		return new float[]
			{
				sx, 0, 0, 0,
				0, sy, 0, 0,
				0, 0, sz, 0,
				0, 0, 0, 1,
			};
	}

	public static float[] translate(float tx, float ty, float tz)
	{
		return new float[]
			{
				1, 0, 0, 0,
				0, 1, 0, 0,
				0, 0, 1, 0,
				tx, ty, tz, 1,
			};
	}

	public static float[] rotateX(float rx)
	{
		float s = (float) Math.sin(rx);
		float c = (float) Math.cos(rx);

		return new float[]
			{
				1, 0, 0, 0,
				0, c, s, 0,
				0, -s, c, 0,
				0, 0, 0, 1,
			};
	}

	public static float[] rotateY(float ry)
	{
		float s = (float) Math.sin(ry);
		float c = (float) Math.cos(ry);

		return new float[]
			{
				c, 0, -s, 0,
				0, 1, 0, 0,
				s, 0, c, 0,
				0, 0, 0, 1,
			};
	}

	public static float[] projection(float w, float h, float n) {
		// Flip Y so positive is up, and reverse depth from 1 at the near plane to 0 infinitely far away
		return new float[]
			{
				2 / w, 0, 0, 0,
				0, -2 / h, 0, 0,
				0, 0, 0, 1,
				0, 0, 2 * n, 0
			};
	}

	public static float[] ortho(float w, float h, float n)
	{
		return new float[]
			{
				2 / w, 0, 0, 0,
				0, 2 / h, 0, 0,
				0, 0, -2 / n, 0,
				0, 0, 0, 1
			};
	}

	/**
	 * Multiplies matrices a and b, storing the result in matrix a.
	 *
	 * @param a column-major 4x4 matrix
	 * @param b column-major 4x4 matrix
	 */
	@SuppressWarnings("PointlessArithmeticExpression")
	public static void mul(float[] a, float[] b)
	{
		final float b00 = b[0 + 0 * 4];
		final float b10 = b[1 + 0 * 4];
		final float b20 = b[2 + 0 * 4];
		final float b30 = b[3 + 0 * 4];
		final float b01 = b[0 + 1 * 4];
		final float b11 = b[1 + 1 * 4];
		final float b21 = b[2 + 1 * 4];
		final float b31 = b[3 + 1 * 4];
		final float b02 = b[0 + 2 * 4];
		final float b12 = b[1 + 2 * 4];
		final float b22 = b[2 + 2 * 4];
		final float b32 = b[3 + 2 * 4];
		final float b03 = b[0 + 3 * 4];
		final float b13 = b[1 + 3 * 4];
		final float b23 = b[2 + 3 * 4];
		final float b33 = b[3 + 3 * 4];

		float ai0 = a[0 * 4]; // row-0 of a
		float ai1 = a[1 * 4];
		float ai2 = a[2 * 4];
		float ai3 = a[3 * 4];
		a[0 * 4] = ai0 * b00 + ai1 * b10 + ai2 * b20 + ai3 * b30;
		a[1 * 4] = ai0 * b01 + ai1 * b11 + ai2 * b21 + ai3 * b31;
		a[2 * 4] = ai0 * b02 + ai1 * b12 + ai2 * b22 + ai3 * b32;
		a[3 * 4] = ai0 * b03 + ai1 * b13 + ai2 * b23 + ai3 * b33;

		ai0 = a[1 + 0 * 4]; // row-1 of a
		ai1 = a[1 + 1 * 4];
		ai2 = a[1 + 2 * 4];
		ai3 = a[1 + 3 * 4];
		a[1 + 0 * 4] = ai0 * b00 + ai1 * b10 + ai2 * b20 + ai3 * b30;
		a[1 + 1 * 4] = ai0 * b01 + ai1 * b11 + ai2 * b21 + ai3 * b31;
		a[1 + 2 * 4] = ai0 * b02 + ai1 * b12 + ai2 * b22 + ai3 * b32;
		a[1 + 3 * 4] = ai0 * b03 + ai1 * b13 + ai2 * b23 + ai3 * b33;

		ai0 = a[2 + 0 * 4]; // row-2 of a
		ai1 = a[2 + 1 * 4];
		ai2 = a[2 + 2 * 4];
		ai3 = a[2 + 3 * 4];
		a[2 + 0 * 4] = ai0 * b00 + ai1 * b10 + ai2 * b20 + ai3 * b30;
		a[2 + 1 * 4] = ai0 * b01 + ai1 * b11 + ai2 * b21 + ai3 * b31;
		a[2 + 2 * 4] = ai0 * b02 + ai1 * b12 + ai2 * b22 + ai3 * b32;
		a[2 + 3 * 4] = ai0 * b03 + ai1 * b13 + ai2 * b23 + ai3 * b33;

		ai0 = a[3 + 0 * 4]; // row-3 of a
		ai1 = a[3 + 1 * 4];
		ai2 = a[3 + 2 * 4];
		ai3 = a[3 + 3 * 4];
		a[3 + 0 * 4] = ai0 * b00 + ai1 * b10 + ai2 * b20 + ai3 * b30;
		a[3 + 1 * 4] = ai0 * b01 + ai1 * b11 + ai2 * b21 + ai3 * b31;
		a[3 + 2 * 4] = ai0 * b02 + ai1 * b12 + ai2 * b22 + ai3 * b32;
		a[3 + 3 * 4] = ai0 * b03 + ai1 * b13 + ai2 * b23 + ai3 * b33;
	}

	/**
	 * Multiplies a 4x4 matrix with a 4x1 vector, storing the result in the output vector.
	 *
	 * @param out  where the result should be stored
	 * @param mat4 4x4 column-major matrix
	 * @param vec4 4x1 vector
	 */
	@SuppressWarnings("PointlessArithmeticExpression")
	public static void mulVec(float[] out, float[] mat4, float[] vec4) {
		float a =
			mat4[0 * 4 + 0] * vec4[0] +
			mat4[1 * 4 + 0] * vec4[1] +
			mat4[2 * 4 + 0] * vec4[2] +
			mat4[3 * 4 + 0] * vec4[3];
		float b =
			mat4[0 * 4 + 1] * vec4[0] +
			mat4[1 * 4 + 1] * vec4[1] +
			mat4[2 * 4 + 1] * vec4[2] +
			mat4[3 * 4 + 1] * vec4[3];
		float c =
			mat4[0 * 4 + 2] * vec4[0] +
			mat4[1 * 4 + 2] * vec4[1] +
			mat4[2 * 4 + 2] * vec4[2] +
			mat4[3 * 4 + 2] * vec4[3];
		float d =
			mat4[0 * 4 + 3] * vec4[0] +
			mat4[1 * 4 + 3] * vec4[1] +
			mat4[2 * 4 + 3] * vec4[2] +
			mat4[3 * 4 + 3] * vec4[3];
		out[0] = a;
		out[1] = b;
		out[2] = c;
		out[3] = d;
	}

	/**
	 * Transforms the vector by the matrix, and does a perspective divide.
	 *
	 * @param out  where the result should be stored
	 * @param mat4 4x4 column-major matrix
	 * @param vec4 4x1 vector
	 */
	public static void projectVec(float[] out, float[] mat4, float[] vec4) {
		mulVec(out, mat4, vec4);
		if (out[3] != 0) {
			// The 4th component should retain information about whether the
			// point lies behind the camera
			float reciprocal = 1 / Math.abs(out[3]);
			for (int i = 0; i < 4; i++)
				out[i] *= reciprocal;
		}
	}

	public static void transpose(float[] m) {
		for (int i = 0; i < 4; i++) {
			for (int j = i + 1; j < 4; j++) {
				int a = i * 4 + j;
				int b = j * 4 + i;
				float temp = m[a];
				m[a] = m[b];
				m[b] = temp;
			}
		}
	}

	public static float[] inverse(float[] m) {
		float[] augmented = new float[32];
		System.arraycopy(m, 0, augmented, 0, 16);
		for (int i = 0; i < 4; i++)
			augmented[16 + i * 5] = 1;

		Matrix.solve(augmented, 4, 8);

		float[] inverse = new float[16];
		System.arraycopy(augmented, 16, inverse, 0, 16);
		return inverse;
	}

	public static void extractRow(float[] out, float[] mat4, int rowIndex) {
		System.arraycopy(mat4, 4 * rowIndex, out, 0, out.length);
	}

	public static void extractColumn(float[] out, float[] mat4, int columnIndex) {
		for (int i = 0; i < out.length; i++)
			out[i] = mat4[4 * i + columnIndex];
	}

	public static String format(float[] m) {
		assert m.length == 16;
		return Matrix.format(m, 4, 4);
	}

}
