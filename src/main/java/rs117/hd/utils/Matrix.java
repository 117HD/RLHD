/*
 * Vector utility functions
 * Written in 2024 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package rs117.hd.utils;

import java.text.DecimalFormat;
import java.util.Arrays;

import static rs117.hd.utils.MathUtils.*;

public class Matrix {
	private static final float EPS = 1e-6f;

	/**
	 * Utility class for working with column-major m x n matrices.
	 */

	public static float[] copy(float[] m) {
		return Arrays.copyOf(m, m.length);
	}

	public static void solve(float[] m, int rows, int columns) {
		int square = min(columns, rows);
		columns:
		for (int j = 0; j < square; j++) {
			for (int i = j; i < rows; i++) {
				var f = m[j * rows + i];
				if (abs(f) < EPS)
					continue;

				// Swap the row into the right position
				if (i != j) {
					for (int k = 0; k < rows * columns; k += rows) {
						var tmp = m[k + j];
						m[k + j] = m[k + i];
						m[k + i] = tmp;
					}
				}

				// Divide by the first entry of the row
				if (abs(f - 1) > EPS) {
					f = 1 / f;
					for (int k = 0; k < rows * columns; k += rows)
						m[k + j] *= f;
				}

				// Reduce other rows
				for (int r = 0; r < rows; r++) {
					if (r == j)
						continue;
					var g = m[j * rows + r];
					if (abs(g) > EPS)
						for (int k = 0; k < rows * columns; k += rows)
							m[k + r] -= g * m[k + j];
				}

				continue columns;
			}

			throw new IllegalArgumentException("Linear system does not have a solution");
		}
	}

	public static String format(float[] m, int rows, int columns) {
		String[] f = new String[m.length];
		var format = new DecimalFormat("0.##");
		int maxdigits = 0;
		int maxfractions = 0;
		for (int i = 0; i < rows * columns; i++) {
			float v = m[i];
			if (abs(v) < .01)
				v = 0;
			f[i] = format.format(v);
			var j = f[i].indexOf('.');
			if (j == -1) {
				maxdigits = max(maxdigits, f[i].length());
			} else {
				maxdigits = max(maxdigits, j);
				maxfractions = max(maxfractions, f[i].length() - j);
			}
		}

		StringBuilder str = new StringBuilder();

		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < columns; col++) {
				int idx = col * rows + row;
				int dot = f[idx].indexOf('.');
				if (dot == -1) {
					int padLeft = maxdigits - f[idx].length();
					if (padLeft > 0)
						f[idx] = " ".repeat(padLeft) + f[idx];
					f[idx] += " ".repeat(maxfractions);
				} else {
					int padLeft = maxdigits - dot;
					if (padLeft > 0)
						f[idx] = " ".repeat(padLeft) + f[idx];
					int padRight = maxfractions - (f[idx].length() - dot);
					if (padRight > 0)
						f[idx] += " ".repeat(padRight);
				}

				if (col == 0)
					str.append("[ ");
				str.append(f[idx]).append(" ");
				if (col == columns - 1) {
					str.append("]");
					if (idx != m.length - 1)
						str.append("\n");
				}
			}
		}

		return str.toString();
	}
}
