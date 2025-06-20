package rs117.hd.utils;

import java.text.DecimalFormat;
import java.util.Arrays;

public class Matrix {
	/**
	 * Utility class for working with column-major m x n matrices.
	 */

	public static float[] copy(float[] m) {
		return Arrays.copyOf(m, m.length);
	}

	public static void solve(float[] m, int rows, int columns) {
		int square = Math.min(columns, rows);
		columns:
		for (int j = 0; j < square; j++) {
			for (int i = j; i < rows; i++) {
				var f = m[j * rows + i];
				if (f == 0)
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
				f = 1 / f;
				for (int k = 0; k < rows * columns; k += rows)
					m[k + j] *= f;

				// Add or subtract multiples to reduce other rows
				for (int r = 0; r < rows; r++) {
					if (r == j)
						continue;
					var g = m[j * rows + r];
					if (g != 0)
						for (int k = 0; k < rows * columns; k += rows)
							m[k + r] += -g * m[k + j];
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
			if (Math.abs(v) < .01)
				v = 0;
			f[i] = format.format(v);
			var j = f[i].indexOf('.');
			if (j == -1) {
				maxdigits = Math.max(maxdigits, f[i].length());
			} else {
				maxdigits = Math.max(maxdigits, j);
				maxfractions = Math.max(maxfractions, f[i].length() - j);
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
