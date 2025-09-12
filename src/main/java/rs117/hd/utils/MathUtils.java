/*
 * Math utility functions.
 * Written in 2025 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package rs117.hd.utils;

import java.util.Arrays;
import java.util.Random;
import javax.annotation.Nullable;

/**
 * Math utility functions similar to GLSL, including vector operations on raw float arrays.
 * Usability and conciseness is prioritized, however most methods at least allow avoiding unnecessary allocations.
 * Wherever it makes sense, inputs of different sizes are allowed, where shorter inputs will be repeated to fill the output vector.
 * When automatically determining the length of the output vector, it will equal the maximum length of the input vectors.
 * Some regular Java math function aliases are included to minimize the need for manual type casting.
 */
public class MathUtils {
	public static final Random RAND = new Random();

	public static final long KiB = 1024;
	public static final long MiB = KiB * KiB;
	public static final long GiB = MiB * KiB;

	public static final float EPSILON = 1.1920929e-7f; // Float epsilon from JOGL
	public static final float MAX_FLOAT_WITH_128TH_PRECISION = 1 << 16;

	public static final float E = (float) Math.E;

	public static final float PI = (float) Math.PI;
	public static final float TWO_PI = PI * 2;
	public static final float HALF_PI = PI / 2;
	public static final float QUARTER_PI = PI / 4;

	public static final float DEG_TO_RAD = TWO_PI / 360;
	public static final float RAD_TO_DEG = 1 / DEG_TO_RAD;
	public static final float JAU_TO_RAD = TWO_PI / 2048;
	public static final float RAD_TO_JAU = 1 / JAU_TO_RAD;

	public static float[] vec(float... vec) {
		return vec;
	}

	public static float[] vec(int... vec) {
		float[] floats = new float[vec.length];
		for (int i = 0; i < vec.length; i++)
			floats[i] = vec[i];
		return floats;
	}

	public static int[] ivec(int... vec) {
		return vec;
	}

	public static int[] ivec(float... vec) {
		int[] ivec = new int[vec.length];
		for (int i = 0; i < vec.length; i++)
			ivec[i] = (int) vec[i];
		return ivec;
	}

	public static float[] copy(float[] v) {
		return Arrays.copyOf(v, v.length);
	}

	public static int[] copy(int[] v) {
		return Arrays.copyOf(v, v.length);
	}

	public static float[] copyTo(float[] out, @Nullable float[] in, int offset, int len) {
		if (in != null) {
			assert offset + len <= min(out.length, in.length);
			System.arraycopy(in, offset, out, offset, len);
		}
		return out;
	}

	public static float[] copyTo(float[] out, @Nullable float[] in) {
		return copyTo(out, in, 0, in == null ? out.length : min(out.length, in.length));
	}

	public static int[] copyTo(int[] out, @Nullable int[] in, int offset, int len) {
		if (in != null) {
			assert offset + len <= min(out.length, in.length);
			System.arraycopy(in, offset, out, offset, len);
		}
		return out;
	}

	public static int[] copyTo(int[] out, @Nullable int[] in) {
		return copyTo(out, in, 0, in == null ? out.length : min(out.length, in.length));
	}

	public static float[] ensureDefaults(@Nullable float[] in, float[] defaults) {
		return in != null && in.length == defaults.length ? in : copyTo(copy(defaults), in);
	}

	public static int[] ensureDefaults(@Nullable int[] in, int[] defaults) {
		return in != null && in.length == defaults.length ? in : copyTo(copy(defaults), in);
	}

	public static int[] slice(int[] v, int offset) {
		return Arrays.copyOfRange(v, offset, v.length);
	}

	/**
	 * If offset + length surpasses the end of the array, the output will be zero padded.
	 */
	public static int[] slice(int[] v, int offset, int length) {
		assert offset <= v.length;
		return Arrays.copyOfRange(v, offset, offset + length);
	}

	public static float[] slice(float[] v, int offset) {
		return Arrays.copyOfRange(v, offset, v.length);
	}

	/**
	 * If offset + length surpasses the end of the array, the output will be zero padded.
	 */
	public static float[] slice(float[] v, int offset, int length) {
		assert offset <= v.length;
		return Arrays.copyOfRange(v, offset, offset + length);
	}

	public static float[] add(float[] out, float[] a, float... b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i % out.length] + b[i % b.length];
		return out;
	}

	public static float[] add(float[] a, float[] b) {
		return add(new float[max(a.length, b.length)], a, b);
	}

	public static float[] subtract(float[] out, float[] a, float... b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i % a.length] - b[i % b.length];
		return out;
	}

	public static float[] subtract(float[] a, float[] b) {
		return subtract(new float[max(a.length, b.length)], a, b);
	}

	public static float[] multiply(float[] out, float[] a, float... b) {
		for (int i = 0; i < out.length; i++)
			out[i] = a[i % a.length] * b[i % b.length];
		return out;
	}

	public static float[] multiply(float[] a, float... b) {
		return multiply(new float[max(a.length, b.length)], a, b);
	}

	public static float[] divide(float[] out, float[] a, float... b) {
		for (int i = 0; i < out.length; i++) {
			float divisor = b[i % b.length];
			out[i] = a[i % a.length] * (divisor == 0 ? 0 : 1 / divisor);
		}
		return out;
	}

	public static float[] divide(float[] a, float... b) {
		return divide(new float[max(a.length, b.length)], a, b);
	}

	/**
	 * Modulo which returns the answer with the same sign as the modulus.
	 */
	public static float mod(float v, float mod) {
		return v - floor(v / mod) * mod;
	}

	public static int mod(long v, int mod) {
		return (int) (v - (v / mod) * mod);
	}

	public static float mod(double v, float mod) {
		return (float) (v - Math.floor(v / mod) * mod);
	}

	public static float[] mod(float[] out, float[] v, float... mod) {
		for (int i = 0; i < out.length; i++)
			out[i] = mod(v[i % v.length], mod[i % mod.length]);
		return out;
	}

	public static float[] mod(float[] v, float... mod) {
		return mod(new float[max(v.length, mod.length)], v, mod);
	}

	public static float pow(float base, float exp) {
		return (float) Math.pow(base, exp);
	}

	public static float[] pow(float[] out, float[] base, float... exp) {
		for (int i = 0; i < out.length; i++)
			out[i] = pow(base[i % base.length], exp[i % exp.length]);
		return out;
	}

	public static float[] pow(float[] in, float... exp) {
		return pow(new float[max(in.length, exp.length)], in, exp);
	}

	public static float pow2(float v) {
		return v * v;
	}

	public static float[] pow2(float[] out, float... v) {
		for (int i = 0; i < out.length; i++) {
			float f = v[i % out.length];
			out[i] = f * f;
		}
		return out;
	}

	public static float[] pow2(float... v) {
		return pow2(new float[v.length], v);
	}

	public static float exp(float v) {
		return (float) Math.exp(v);
	}

	public static float[] exp(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = exp(v[i % v.length]);
		return out;
	}

	public static float[] exp(float... v) {
		return exp(new float[v.length], v);
	}

	public static float log(float v) {
		return (float) Math.log(v);
	}

	public static float[] log(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = log(v[i % v.length]);
		return out;
	}

	public static float[] log(float... v) {
		return log(new float[v.length], v);
	}

	public static float log2(float v) {
		return log(v) / log(2);
	}

	public static float[] log2(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = log2(v[i % v.length]);
		return out;
	}

	public static float[] log2(float... v) {
		return log2(new float[v.length], v);
	}

	public static float sqrt(float v) {
		return (float) Math.sqrt(v);
	}

	public static float[] sqrt(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = sqrt(v[i % v.length]);
		return out;
	}

	public static float[] sqrt(float... v) {
		return sqrt(new float[v.length], v);
	}

	public static float dot(float[] a, float[] b, int n) {
		assert a.length >= n && b.length >= n;
		float f = 0;
		for (int i = 0; i < n; i++)
			f += a[i] * b[i];
		return f;
	}

	public static float dot(float[] a, float... b) {
		return dot(a, b, min(a.length, b.length));
	}

	public static float dot(float... v) {
		return dot(v, v);
	}

	public static int product(int... v) {
		int product = 1;
		for (int factor : v)
			product *= factor;
		return product;
	}

	public static float product(float... v) {
		float product = 1;
		for (float factor : v)
			product *= factor;
		return product;
	}

	/**
	 * Yields incorrect results if either of the input vectors is used as the output vector.
	 */
	public static float[] cross(float[] out, float[] a, float[] b) {
		out[0] = a[1] * b[2] - a[2] * b[1];
		out[1] = a[2] * b[0] - a[0] * b[2];
		out[2] = a[0] * b[1] - a[1] * b[0];
		return out;
	}

	public static float[] cross(float[] a, float[] b) {
		return cross(new float[3], a, b);
	}

	public static float length(float... v) {
		return (float) Math.sqrt(dot(v, v));
	}

	public static float distance(float[] a, float[] b, int n) {
		return (float) Math.sqrt(dot(a, a, n) - 2 * dot(a, b, n) + dot(b, b, n));
	}

	public static float distance(float[] a, float[] b) {
		return distance(a, b, min(a.length, b.length));
	}

	public static float[] normalize(float[] out, float... v) {
		return divide(out, v, length(v));
	}

	public static float[] normalize(float... v) {
		return normalize(new float[v.length], v);
	}

	public static float abs(float v) {
		return Math.abs(v);
	}

	public static int abs(int v) {
		return Math.abs(v);
	}

	public static long abs(long v) {
		return Math.abs(v);
	}

	public static float[] abs(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = abs(v[i % v.length]);
		return out;
	}

	public static float[] abs(float[] v) {
		return abs(new float[v.length], v);
	}

	public static int floor(float v) {
		return (int) Math.floor(v);
	}

	public static int[] floor(int[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = floor(v[i % v.length]);
		return out;
	}

	public static int[] floor(float[] v) {
		return floor(new int[v.length], v);
	}

	public static int ceil(float v) {
		return (int) Math.ceil(v);
	}

	public static int[] ceil(int[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = ceil(v[i % v.length]);
		return out;
	}

	public static int[] ceil(float[] v) {
		return ceil(new int[v.length], v);
	}

	public static int round(float v) {
		return Math.round(v);
	}

	public static long round(double v) {
		return Math.round(v);
	}

	public static int[] round(int[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = round(v[i % v.length]);
		return out;
	}

	public static int[] round(float[] v) {
		return round(new int[v.length], v);
	}

	public static float[] roundf(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = round(v[i % v.length]);
		return out;
	}

	public static float[] roundf(float[] v) {
		return roundf(new float[v.length], v);
	}

	public static float min(float a, float b) {
		return Math.min(a, b);
	}

	public static int min(int a, int b) {
		return Math.min(a, b);
	}

	public static long min(long a, long b) {
		return Math.min(a, b);
	}

	public static float min(float... v) {
		assert v.length > 0;
		var min = v[0];
		for (var x : v)
			min = min(min, x);
		return min;
	}

	public static float[] min(float[] out, float[] a, float... b) {
		for (int i = 0; i < out.length; i++)
			out[i] = min(a[i % a.length], b[i % b.length]);
		return out;
	}

	public static float[] min(float[] a, float... b) {
		return min(new float[max(a.length, b.length)], a, b);
	}

	public static int min(int... v) {
		assert v.length > 0;
		var min = v[0];
		for (var x : v)
			min = min(min, x);
		return min;
	}

	public static int[] min(int[] out, int[] a, int... b) {
		for (int i = 0; i < out.length; i++)
			out[i] = min(a[i % a.length], b[i % b.length]);
		return out;
	}

	public static int[] min(int[] a, int... b) {
		return min(new int[max(a.length, b.length)], a, b);
	}

	public static float max(float a, float b) {
		return Math.max(a, b);
	}

	public static int max(int a, int b) {
		return Math.max(a, b);
	}

	public static long max(long a, long b) {
		return Math.max(a, b);
	}

	public static float max(float... v) {
		assert v.length > 0;
		var max = v[0];
		for (var x : v)
			max = max(max, x);
		return max;
	}

	public static float[] max(float[] out, float[] a, float... b) {
		for (int i = 0; i < out.length; i++)
			out[i] = max(a[i % a.length], b[i % b.length]);
		return out;
	}

	public static float[] max(float[] a, float... b) {
		return max(new float[max(a.length, b.length)], a, b);
	}

	public static int max(int... v) {
		assert v.length > 0;
		var max = v[0];
		for (var x : v)
			max = max(max, x);
		return max;
	}

	public static int[] max(int[] out, int[] a, int... b) {
		for (int i = 0; i < out.length; i++)
			out[i] = max(a[i % a.length], b[i % b.length]);
		return out;
	}

	public static int[] max(int[] a, int... b) {
		return max(new int[max(a.length, b.length)], a, b);
	}

	public static float clamp(float v, float min, float max) {
		return min(max(v, min), max);
	}

	public static float clamp(double v, float min, float max) {
		return clamp((float) v, min, max);
	}

	public static int clamp(int v, int min, int max) {
		return min(max(v, min), max);
	}

	public static float[] clamp(float[] out, float[] v, float[] min, float... max) {
		for (int i = 0; i < out.length; i++)
			out[i] = clamp(v[i % v.length], min[i % min.length], max[i % max.length]);
		return out;
	}

	public static float[] clamp(float[] out, float[] v, float min, float... max) {
		return clamp(out, v, vec(min), max);
	}

	public static float[] clamp(float[] v, float[] min, float... max) {
		return clamp(new float[max(v.length, min.length, max.length)], v, min, max);
	}

	public static float[] clamp(float[] v, float min, float... max) {
		return clamp(new float[max(v.length, max.length)], v, vec(min), max);
	}

	public static float saturate(float v) {
		return clamp(v, 0, 1);
	}

	public static float saturate(double v) {
		return saturate((float) v);
	}

	public static float[] saturate(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = saturate(v[i % v.length]);
		return out;
	}

	public static float[] saturate(float... v) {
		return saturate(new float[v.length], v);
	}

	public static float fract(float v) {
		return mod(v, 1);
	}

	public static float[] fract(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = fract(v[i % out.length]);
		return out;
	}

	public static float[] fract(float... v) {
		return fract(new float[v.length], v);
	}

	public static float sign(float v) {
		return v < 0 ? -1 : 1;
	}

	public static float[] sign(float[] out, float... v) {
		for (int i = 0; i < out.length; i++)
			out[i] = sign(v[i % out.length]);
		return out;
	}

	public static float[] sign(float... v) {
		return sign(new float[v.length], v);
	}

	public static float mix(float v0, float v1, float factor) {
		return v0 * (1 - factor) + v1 * factor;
	}

	public static float[] mix(float[] out, float[] v0, float[] v1, float... factor) {
		for (int i = 0; i < out.length; i++)
			out[i] = mix(v0[i % v0.length], v1[i % v1.length], factor[i % factor.length]);
		return out;
	}

	public static float[] mix(float[] v0, float[] v1, float... factor) {
		return mix(new float[max(v0.length, v1.length, factor.length)], v0, v1, factor);
	}

	public static float smoothstep(float v0, float v1, float factor) {
		float t = saturate((factor - v0) / (v1 - v0));
		return t * t * (3 - 2 * t);
	}

	public static float[] smoothstep(float[] out, float[] v0, float[] v1, float... factor) {
		for (int i = 0; i < out.length; i++)
			out[i] = smoothstep(v0[i % v0.length], v1[i % v1.length], factor[i % factor.length]);
		return out;
	}

	public static float[] smoothstep(float[] v0, float[] v1, float... factor) {
		return smoothstep(new float[max(v0.length, v1.length, factor.length)], v0, v1, factor);
	}

	public static float sum(float... v) {
		float sum = 0;
		for (float value : v)
			sum += value;
		return sum;
	}

	public static float avg(float... v) {
		return sum(v) / v.length;
	}

	public static float sin(float rad) {
		return (float) Math.sin(rad);
	}

	public static float cos(float rad) {
		return (float) Math.cos(rad);
	}

	public static float tan(float rad) {
		return (float) Math.tan(rad);
	}
}
