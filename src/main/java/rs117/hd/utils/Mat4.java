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

import static rs117.hd.utils.MathUtils.*;

public class Mat4
{
	/**
	 * Utility class for working with column-major 4 x 4 matrices.
	 */

	public static float[] identity()
	{
		return new float[] {
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			0, 0, 0, 1,
		};
	}

	public static float[] scale(float sx, float sy, float sz)
	{
		return new float[] {
			sx, 0, 0, 0,
			0, sy, 0, 0,
			0, 0, sz, 0,
			0, 0, 0, 1,
		};
	}

	public static float[] translate(float tx, float ty, float tz)
	{
		return new float[] {
			1, 0, 0, 0,
			0, 1, 0, 0,
			0, 0, 1, 0,
			tx, ty, tz, 1,
		};
	}

	public static float[] rotateX(float rx)
	{
		float s = sin(rx);
		float c = cos(rx);

		return new float[] {
			1, 0, 0, 0,
			0, c, s, 0,
			0, -s, c, 0,
			0, 0, 0, 1,
		};
	}

	public static float[] rotateY(float ry)
	{
		float s = sin(ry);
		float c = cos(ry);

		return new float[] {
			c, 0, -s, 0,
			0, 1, 0, 0,
			s, 0, c, 0,
			0, 0, 0, 1,
		};
	}

	/**
	 * Infinite far plane, Reverse-Z perspective matrix.
	 * Depth = 1 at near plane, 0 infinitely far away.
	 */
	public static float[] perspectiveInfiniteReverseZ(float w, float h, float n) {
		return new float[]{
			2 / w, 0,     0,  0,
			0,    -2 / h, 0,  0,
			0,     0,     0,  1,
			0,     0,     2 * n, 0
		};
	}

	/**
	 * Infinite far plane, normal-Z perspective matrix.
	 * Depth = 0 at near plane, 1 infinitely far away.
	 */
	public static float[] perspectiveInfinite(float w, float h, float n) {
		return new float[]{
			2 / w, 0,     0,  0,
			0,    -2 / h, 0,  0,
			0,     0,    -1, -1,
			0,     0,    -2 * n, 0
		};
	}

	/**
	 * Finite far plane, Reverse-Z perspective matrix.
	 * Depth = 1 at near plane, 0 at far plane.
	 */
	public static float[] perspectiveReverseZ(float w, float h, float n, float f) {
		float nf = n / f;
		float a = (1 + nf) / (nf - 1);
		float b = a * n - n;
		float c = 1; // reversed depth (positive)
		return new float[]{
			2 / w, 0,     0,  0,
			0,    -2 / h, 0,  0,
			0,     0,     a,  c,
			0,     0,     b,  0
		};
	}

	/**
	 * Create a perspective projection matrix matching vanilla OSRS projection, with a finite far plane.
	 *
	 * @param w viewport width
	 * @param h viewport height
	 * @param n near plane
	 * @param f far plane
	 * @return 4x4 column-major matrix
	 */
	public static float[] perspective(float w, float h, float n, float f) {
		// Same projection as vanilla, except with slightly more depth precision, and a usable far plane for clipping calculations
		w = 2 / w;
		h = 2 / h;
		float a = (1 + n / f) / (n / f - 1);
		float b = a * n - n;
		float c = -1; // perspective divide by -z
		return new float[]
			{
				w, 0, 0, 0,
				0, h, 0, 0,
				0, 0, a, c,
				0, 0, b, 0
			};
	}

	public static float[] orthographic(float w, float h, float n)
	{
		return new float[] {
			2 / w, 0, 0, 0,
			0, -2 / h, 0, 0,
			0, 0, 2 / n, 0,
			0, 0, 0, 1
		};
	}

	public static float[] orthographic(float w, float h, float n, float f)
	{
		return new float[] {
			2.0f / w, 0, 0, 0,
			0, -2.0f / h, 0, 0,
			0, 0, 1.0f / (f - n), 0,
			0, 0, -(f + n) / (f - n), 1
		};
	}

	public static float[] orthographicReverseZ(float w, float h, float n, float f)
	{
		return new float[] {
			2.0f / w, 0, 0, 0,
			0, -2.0f / h, 0, 0,
			0, 0, 1.0f / (n - f), 0,
			0, 0, f / (f - n), 1
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
	 * Multiplies a 4x4 matrix with a 4x1 vector, storing the result in the output vector, which may be the same as the input vector.
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

	public static float[][] extractFrustumCorners(float[] invViewProj, float[][] corners) {
		int index = 0;

		for (int z = 0; z <= 1; z++) {
			for (int y = 0; y <= 1; y++) {
				for (int x = 0; x <= 1; x++) {
					// Convert from 0/1 to -1/+1 for NDC
					float ndcX = x * 2.0f - 1.0f;
					float ndcY = y * 2.0f - 1.0f;
					float ndcZ = z * 2.0f - 1.0f;

					float[] ndc = new float[] { ndcX, ndcY, ndcZ, 1.0f };
					float[] world = new float[4];

					projectVec(world, invViewProj, ndc);

					// Store world-space XYZ
					corners[index][0] = world[0];
					corners[index][1] = world[1];
					corners[index][2] = world[2];
					index++;
				}
			}
		}

		return corners;
	}

	public static void extractPlanes(float[] mat, float[][] planes) {
		// Each plane is defined as: ax + by + cz + d = 0
		// Extract rows from the matrix (column-major order)
		float m00 = mat[0], m01 = mat[4], m02 = mat[8], m03 = mat[12];
		float m10 = mat[1], m11 = mat[5], m12 = mat[9], m13 = mat[13];
		float m20 = mat[2], m21 = mat[6], m22 = mat[10], m23 = mat[14];
		float m30 = mat[3], m31 = mat[7], m32 = mat[11], m33 = mat[15];

		// Left = row3 + row0
		float[] left = planes[0];
		left[0] = m30 + m00;
		left[1] = m31 + m01;
		left[2] = m32 + m02;
		left[3] = m33 + m03;
		normalizePlane(left, left);

		// Right = row3 - row0
		float[] right = planes[1];
		right[0] = m30 - m00;
		right[1] = m31 - m01;
		right[2] = m32 - m02;
		right[3] = m33 - m03;
		normalizePlane(right, right);

		// Bottom = row3 + row1
		float[] bottom = planes[2];
		bottom[0] = m30 + m10;
		bottom[1] = m31 + m11;
		bottom[2] = m32 + m12;
		bottom[3] = m33 + m13;
		normalizePlane(bottom, bottom);

		// Top = row3 - row1
		float[] top = planes[3];
		top[0] = m30 - m10;
		top[1] = m31 - m11;
		top[2] = m32 - m12;
		top[3] = m33 - m13;
		normalizePlane(top, top);

		// Near = row3 + row2
		float[] near = planes[4];
		near[0] = m30 + m20;
		near[1] = m31 + m21;
		near[2] = m32 + m22;
		near[3] = m33 + m23;
		normalizePlane(near, near);

		// Far = row3 - row2
		float[] far = planes[5];
		far[0] = m30 - m20;
		far[1] = m31 - m21;
		far[2] = m32 - m22;
		far[3] = m33 - m23;
		normalizePlane(far, far);
	}

	/**
	 * Multiplies a 4x4 matrix with a 3x1 vector, storing the result in the output vector, which may be the same as the input vector.
	 * Transforms a 3D position by a 4x4 affine matrix (w = 1.0), ignoring the resulting W component.
	 *
	 * @param out  where the result should be stored
	 * @param mat4 4x4 column-major matrix
	 * @param vec3 3x1 vector
	 */
	@SuppressWarnings("PointlessArithmeticExpression")
	public static void transformVecAffine(float[] out, float[] mat4, float[] vec3) {
		float a =
			mat4[0 * 4 + 0] * vec3[0] +
			mat4[1 * 4 + 0] * vec3[1] +
			mat4[2 * 4 + 0] * vec3[2] +
			mat4[3 * 4 + 0] * 1.0f;
		float b =
			mat4[0 * 4 + 1] * vec3[0] +
			mat4[1 * 4 + 1] * vec3[1] +
			mat4[2 * 4 + 1] * vec3[2] +
			mat4[3 * 4 + 1] * 1.0f;
		float c =
			mat4[0 * 4 + 2] * vec3[0] +
			mat4[1 * 4 + 2] * vec3[1] +
			mat4[2 * 4 + 2] * vec3[2] +
			mat4[3 * 4 + 2] * 1.0f;
		out[0] = a;
		out[1] = b;
		out[2] = c;
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
		float[] augmented = slice(m, 0, 32);
		augmented[16] = augmented[21] = augmented[26] = augmented[31] = 1;
		Matrix.solve(augmented, 4, 8);
		return slice(augmented, 16);
	}

	public static void clipFrustumToDistance(float[][] frustumCorners, float maxDistance) {
		if (frustumCorners.length != 8) {
			return;
		}

		// Clip Far Plane Corners
		for (int i = 4; i < frustumCorners.length; i++) {
			float[] nearCorner = frustumCorners[i - 4];
			float[] farCorner = frustumCorners[i];
			float[] nearToFarVec = subtract(nearCorner, farCorner);
			float len = length(nearToFarVec);

			if (len > 1e-5f && len > maxDistance) {
				normalize(nearToFarVec, nearToFarVec);
				float[] clipped = multiply(nearToFarVec, maxDistance);
				frustumCorners[i] = add(clipped, nearCorner);
			}
		}
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
