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
package rs117.hd.data.materials;

import net.runelite.api.Perspective;

public enum UvType
{
	VANILLA,
	GEOMETRY,
	MODEL_XY((uvw, i, x, y, z) -> { uvw[i] = x; uvw[i + 1] = y; uvw[i + 2] = z; }),
	MODEL_XZ((uvw, i, x, y, z) -> { uvw[i] = x; uvw[i + 1] = z; uvw[i + 2] = y; }),
	MODEL_YZ((uvw, i, x, y, z) -> { uvw[i] = y; uvw[i + 1] = z; uvw[i + 2] = x; }),
	WORLD_XY(new float[] { 0, 0, -1 }),
	WORLD_XZ(new float[] { 0, -1, 0 }),
	WORLD_YZ(new float[] { -1, 0, 0 });

	@FunctionalInterface
	public interface UvGenerator {
		void computeUvw(float[] out, int offset, float x, float y, float z);
	}

	public final boolean worldUvs;
	private final UvGenerator generator;

	UvType() {
		worldUvs = false;
		generator = null;
	}

	UvType(UvGenerator generator) {
		worldUvs = false;
		this.generator = generator;
	}

	UvType(float[] normal) {
		worldUvs = true;
		generator = (uvw, i, scale, _1, _2) -> {
			uvw[i] = scale * normal[0];
			uvw[i + 1] = scale * normal[1];
			uvw[i + 2] = scale * normal[2];
		};
	}

	public void computeModelUvw(float[] out, int offset, float scale, float x, float y, float z) {
		assert generator != null : this + " does not support computing UVs";
		x /= scale * Perspective.LOCAL_TILE_SIZE;
		y /= scale * Perspective.LOCAL_TILE_SIZE;
		z /= scale * Perspective.LOCAL_TILE_SIZE;
		generator.computeUvw(out, offset, x, y, z);
	}

	public void computeWorldUvw(float[] out, int offset, float scale) {
		assert generator != null : this + " does not support computing UVs";
		generator.computeUvw(out, offset, scale, 0, 0);
	}
}
