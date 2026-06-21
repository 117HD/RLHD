/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
package rs117.hd.scene;

import java.util.Random;

/**
 * Generates a fixed list of stars once at startup so they can be drawn as point
 * sprites (cost scales with star count) rather than searched for per sky pixel
 * (cost scales with screen pixels). Mirrors the two-layer look the procedural
 * starfield used: a sparse layer of bright, larger stars over a dense layer of
 * dim, small ones, with a power-law brightness distribution and stellar tints.
 */
public class StarField {
	// Per-vertex layout written to the VBO, in floats:
	//   position.xyz (unit direction), size, brightness, color.rgb  => 8 floats
	public static final int FLOATS_PER_STAR = 8;

	// Star counts per layer. The procedural field had ~18-24% of cells populated
	// across grids of scale 80 and 200; these counts reproduce a similar on-sky
	// density without being tied to screen resolution.
	private static final int BRIGHT_STAR_COUNT = 400;  // layer 0: sparse/bright/large
	private static final int DIM_STAR_COUNT = 1200;    // layer 1: dense/dim/small

	private static final long SEED = 0x117D511A5L;

	public final int starCount = BRIGHT_STAR_COUNT + DIM_STAR_COUNT;
	public final float[] vertexData;

	public StarField() {
		vertexData = new float[starCount * FLOATS_PER_STAR];
		var rng = new Random(SEED);
		int offset = 0;
		// Layer 0: bright, sparse, larger.  Layer 1: dim, dense, smaller.
		offset = generateLayer(rng, vertexData, offset, BRIGHT_STAR_COUNT, 1.2f, 1.0f);
		generateLayer(rng, vertexData, offset, DIM_STAR_COUNT, 0.4f, 0.66f);
	}

	private static int generateLayer(Random rng, float[] out, int offset, int count, float maxBrightness, float sizeScale) {
		for (int i = 0; i < count; i++) {
			// Uniform point on the unit sphere.
			double z = rng.nextDouble() * 2.0 - 1.0;
			double t = rng.nextDouble() * 2.0 * Math.PI;
			double r = Math.sqrt(Math.max(0.0, 1.0 - z * z));
			float dx = (float) (r * Math.cos(t));
			float dy = (float) z;
			float dz = (float) (r * Math.sin(t));

			// Power-law brightness: many dim, few bright (matches pow(seed, 2.5)).
			float brightnessSeed = rng.nextFloat();
			float brightness = (float) Math.pow(brightnessSeed, 2.5) * maxBrightness;

			// Per-star size variation (0.5x to 1.0x), scaled per layer.
			float size = (0.5f + rng.nextFloat() * 0.5f) * sizeScale;

			// Stellar color tint by population fraction (same bands as the shader).
			float c = rng.nextFloat();
			float tr, tg, tb;
			if (c < 0.06f)      { tr = 1.0f; tg = 0.70f; tb = 0.45f; } // warm orange
			else if (c < 0.18f) { tr = 1.0f; tg = 0.90f; tb = 0.65f; } // golden yellow
			else if (c < 0.30f) { tr = 1.0f; tg = 0.95f; tb = 0.85f; } // pale warm white
			else if (c < 0.70f) { tr = 1.0f; tg = 1.00f; tb = 1.00f; } // neutral white
			else if (c < 0.85f) { tr = 0.85f; tg = 0.92f; tb = 1.0f; } // pale blue-white
			else                { tr = 0.70f; tg = 0.80f; tb = 1.0f; } // cool blue

			out[offset++] = dx;
			out[offset++] = dy;
			out[offset++] = dz;
			out[offset++] = size;
			out[offset++] = brightness;
			out[offset++] = tr;
			out[offset++] = tg;
			out[offset++] = tb;
		}
		return offset;
	}
}
