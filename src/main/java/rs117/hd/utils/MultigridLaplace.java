package rs117.hd.utils;

/**
 * Multigrid-accelerated Laplace solver for 2D height maps.
 *
 * <p>Fills free (non-fixed) cells by solving the discrete Laplace equation
 * (∇²u = 0) with homogeneous Neumann boundary conditions on the domain edges.
 * A coarse-to-fine resolution pyramid lets the coarsest (cheapest) level do
 * the heavy lifting; each finer level only needs a few SOR smoothing passes
 * on the upsampled warm-start.
 *
 * <p>Both input arrays are row-major flattened: index {@code row * width + col}.
 */
public class MultigridLaplace {

	/**
	 * Solve in-place, returning a new array with free cells filled.
	 *
	 * @param heights      Flattened (H×W) float array, row-major.
	 *                     Fixed cells hold their true value; free (ocean) cells
	 *                     should be pre-filled with a sensible prior (e.g. the
	 *                     average depth for that water type).
	 * @param fixed        Flattened (H×W) boolean array, row-major.
	 *                     {@code true} = pinned (land or contour line).
	 *                     {@code false} = free cell to be solved.
	 * @param width        Grid width  (W).
	 * @param height       Grid height (H).
	 * @param nLevels      Pyramid depth. Each level halves both dimensions.
	 *                     4 is a good default for 184×184.
	 * @param itersPerLevel SOR iterations per level, <em>coarsest-first</em>.
	 *                     Length must be ≥ {@code nLevels}.
	 * @param omega        SOR over-relaxation factor.
	 *                     1.0 = pure Jacobi (always stable, safe default).
	 *                     Values in (1, 2) converge faster but can diverge
	 *                     from a cold start; use 1.0 when unsure.
	 * @return New flattened (H×W) array with free cells solved.
	 */
	public static float[] solve(
		float[]   heights,
		boolean[] fixed,
		int       width,
		int       height,
		int       nLevels,
		int[]     itersPerLevel,
		float     omega) {

		if (itersPerLevel.length < nLevels)
			throw new IllegalArgumentException(
				"itersPerLevel must have at least nLevels entries (coarsest-first)");

		final float om1 = 1.0f - omega;

		// ── pyramid shapes, coarsest-first ───────────────────────────────
		// shapes[i] = {h, w} at pyramid level i; index 0 = coarsest.
		int[] pyrH = new int[nLevels];
		int[] pyrW = new int[nLevels];
		{
			int h = height, w = width;
			for (int i = 0; i < nLevels; i++) {
				pyrH[nLevels - 1 - i] = h;   // fill fine-first, reverse below
				pyrW[nLevels - 1 - i] = w;
				h = Math.max(h / 2, 1);
				w = Math.max(w / 2, 1);
			}
			// now pyrH[0]/pyrW[0] = coarsest, pyrH[nLevels-1]/pyrW[nLevels-1] = finest
			// (the fill order above already gives this — double-check):
			// level nLevels-1-0 = nLevels-1 got h=height, w=width  → finest at [nLevels-1] ✓
		}

		// ── pre-allocate all pyramid buffers ─────────────────────────────
		float[][]   ORIG = new float[nLevels][];   // downsampled original heights
		float[][]   WORK = new float[nLevels][];   // working solution per level
		boolean[][] FMSK = new boolean[nLevels][]; // downsampled fixed mask
		float[][]   FV   = new float[nLevels][];   // fixed-value snapshot
		float[][]   PAD  = new float[nLevels][];   // ghost-cell padded buffer
		float[][]   NEW  = new float[nLevels][];   // Laplacian scratch buffer

		for (int lvl = 0; lvl < nLevels; lvl++) {
			int sz = pyrH[lvl] * pyrW[lvl];
			ORIG[lvl] = new float[sz];
			WORK[lvl] = new float[sz];
			FMSK[lvl] = new boolean[sz];
			FV[lvl]   = new float[sz];
			int padSz = (pyrH[lvl] + 2) * (pyrW[lvl] + 2);
			PAD[lvl]  = new float[padSz];
			NEW[lvl]  = new float[sz];
		}

		// ── build downsampled pyramids, fine → coarse ─────────────────────
		// Finest level = nLevels-1: copy inputs directly.
		System.arraycopy(heights, 0, ORIG[nLevels - 1], 0, heights.length);
		System.arraycopy(fixed,   0, FMSK[nLevels - 1], 0, fixed.length);

		for (int lvl = nLevels - 1; lvl > 0; lvl--) {
			int fH = pyrH[lvl], fW = pyrW[lvl];  // fine dimensions
			int cH = fH / 2,    cW = fW / 2;     // coarse dimensions (= pyrH[lvl-1])
			float[]   uFine = ORIG[lvl];
			boolean[] fFine = FMSK[lvl];
			float[]   uCoarse = ORIG[lvl - 1];
			boolean[] fCoarse = FMSK[lvl - 1];

			for (int cr = 0; cr < cH; cr++) {
				int fr = cr * 2;
				for (int cc = 0; cc < cW; cc++) {
					int fc = cc * 2;
					// area-average of the 2×2 block
					uCoarse[cr * cW + cc] = 0.25f * (
						uFine[ fr      * fW + fc    ] +
						uFine[ fr      * fW + fc + 1] +
						uFine[(fr + 1) * fW + fc    ] +
						uFine[(fr + 1) * fW + fc + 1]);
					// coarse cell is fixed if ANY fine cell in the block is fixed
					fCoarse[cr * cW + cc] =
						fFine[ fr      * fW + fc    ] ||
						fFine[ fr      * fW + fc + 1] ||
						fFine[(fr + 1) * fW + fc    ] ||
						fFine[(fr + 1) * fW + fc + 1];
				}
			}
		}

		// ── coarse → fine solve ───────────────────────────────────────────
		// Initialise coarsest working buffer from downsampled heights + prior.
		System.arraycopy(ORIG[0], 0, WORK[0], 0, ORIG[0].length);

		for (int level = 0; level < nLevels; level++) {
			int H = pyrH[level], W = pyrW[level];
			int Wp = W + 2;   // padded width

			float[]   orig = ORIG[level];
			float[]   u    = WORK[level];
			boolean[] f    = FMSK[level];
			float[]   fv   = FV[level];
			float[]   pad  = PAD[level];
			float[]   neu  = NEW[level];

			// ── upsample warm-start from previous (coarser) level ────────
			if (level > 0) {
				int pH = pyrH[level - 1], pW = pyrW[level - 1];
				float[] uPrev = WORK[level - 1];

				// nearest-neighbour 2× upsample, clamped to uPrev dimensions
				// to handle odd target sizes (e.g. 737 = 2×368 + 1)
				for (int pr = 0; pr < pH; pr++) {
					for (int pc = 0; pc < pW; pc++) {
						float val = uPrev[pr * pW + pc];
						int r0 = pr * 2, c0 = pc * 2;
						// write the four fine cells that this coarse cell maps to,
						// guarded against overshooting when H or W is odd
						if (r0     < H && c0     < W) u[ r0      * W + c0    ] = val;
						if (r0 + 1 < H && c0     < W) u[(r0 + 1) * W + c0    ] = val;
						if (r0     < H && c0 + 1 < W) u[ r0      * W + c0 + 1] = val;
						if (r0 + 1 < H && c0 + 1 < W) u[(r0 + 1) * W + c0 + 1] = val;
					}
				}

				// restore pinned cells from original heights at this resolution
				for (int i = 0; i < H * W; i++) {
					if (f[i]) u[i] = orig[i];
				}
			}

			// fixed-value snapshot: true value where pinned, 0 elsewhere
			for (int i = 0; i < H * W; i++) {
				fv[i] = f[i] ? orig[i] : 0.0f;
			}

			// ── SOR iterations ────────────────────────────────────────────
			int nIter = itersPerLevel[level];   // coarsest-first indexing

			for (int iter = 0; iter < nIter; iter++) {

				// --- sync interior of padded buffer (Neumann ghost cells) --
				// Interior: pad[r+1][c+1] = u[r][c]
				for (int r = 0; r < H; r++) {
					int uRow  = r * W;
					int pRow  = (r + 1) * Wp;
					for (int c = 0; c < W; c++) {
						pad[pRow + c + 1] = u[uRow + c];
					}
				}
				// Top ghost row ← first real row
				for (int c = 0; c < Wp; c++) pad[c] = pad[Wp + c];
				// Bottom ghost row ← last real row
				int lastReal = H * Wp, ghostBot = (H + 1) * Wp;
				for (int c = 0; c < Wp; c++) pad[ghostBot + c] = pad[lastReal + c];
				// Left ghost column ← first real column
				for (int r = 0; r < H + 2; r++) pad[r * Wp] = pad[r * Wp + 1];
				// Right ghost column ← last real column
				for (int r = 0; r < H + 2; r++) pad[r * Wp + W + 1] = pad[r * Wp + W];

				// --- 5-point Laplacian average + SOR blend ----------------
				for (int r = 0; r < H; r++) {
					int uRow = r * W;
					int pRow = (r + 1) * Wp;   // pad row for r (offset by 1)
					for (int c = 0; c < W; c++) {
						int pc = c + 1;
						float avg = 0.25f * (
							pad[(r    ) * Wp + pc] +   // up    (ghost row above)
							pad[(r + 2) * Wp + pc] +   // down
							pad[pRow    + pc - 1 ] +   // left
							pad[pRow    + pc + 1 ]);   // right
						int i = uRow + c;
						// SOR: new = ω*avg + (1−ω)*old; pin fixed cells
						neu[i] = f[i] ? fv[i] : (omega * avg + om1 * u[i]);
					}
				}

				// swap new → u
				System.arraycopy(neu, 0, u, 0, H * W);
			}
		}

		// Return a copy of the finest-level working buffer
		float[] result = new float[width * height];
		System.arraycopy(WORK[nLevels - 1], 0, result, 0, result.length);
		return result;
	}

	// ── convenience overload with sensible defaults ───────────────────────

	/**
	 * Solve with default parameters (4 levels, iters = {50,30,15,6}, ω = 1.0).
	 */
	public static float[] solve(
		float[]   heights,
		boolean[] fixed,
		int       width,
		int       height) {
		return solve(heights, fixed, width, height,
			5, new int[]{100, 100, 50, 50, 25}, 1.f);
	}
}
