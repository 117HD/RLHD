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
package rs117.hd.scene.model_overrides;

public enum UvType {
	VANILLA,
	GEOMETRY,
	// TODO: move MODEL_* computation to compute shader for efficiency
	MODEL_XY(true, (uvw, i, x, y, z) -> {
		uvw[i] = x;
		uvw[i + 1] = y;
		uvw[i + 2] = z;
	}),
	MODEL_XY_MIRROR_A(MODEL_XY, UvType::mirrorDiagonally),
	MODEL_XY_MIRROR_B(MODEL_XY, (uvw, i) -> {
		uvw[i + 1] = 1 - uvw[i + 1];
		mirrorDiagonally(uvw, i);
	}),
	MODEL_XZ(true, (uvw, i, x, y, z) -> {
		uvw[i] = x;
		uvw[i + 1] = z;
		uvw[i + 2] = y;
	}),
	MODEL_XZ_MIRROR_A(MODEL_XZ, UvType::mirrorDiagonally),
	MODEL_XZ_MIRROR_B(MODEL_XZ, (uvw, i) -> {
		uvw[i + 1] = 1 - uvw[i + 1];
		mirrorDiagonally(uvw, i);
	}),
	MODEL_YZ(true, (uvw, i, x, y, z) -> {
		uvw[i] = y;
		uvw[i + 1] = z;
		uvw[i + 2] = x;
	}),
	MODEL_YZ_MIRROR_A(MODEL_YZ, UvType::mirrorDiagonally),
	MODEL_YZ_MIRROR_B(MODEL_YZ, (uvw, i) -> {
		uvw[i + 1] = 1 - uvw[i + 1];
		mirrorDiagonally(uvw, i);
	}),
	WORLD_XY(new float[] { 0, 0, -1 }),
	WORLD_XZ(new float[] { 0, -1, 0 }),
	WORLD_YZ(new float[] { -1, 0, 0 }),
	BOX,
	;

	public final boolean worldUvs;
	public final boolean orientationDependent;
	private final UvGenerator generator;

	UvType() {
		worldUvs = false;
		orientationDependent = false;
		generator = null;
	}

	UvType(boolean orientationDependent, UvGenerator generator) {
		this.worldUvs = false;
		this.orientationDependent = orientationDependent;
		this.generator = generator;
	}

	UvType(UvType inherit, UvProcessor processor) {
		worldUvs = inherit.worldUvs;
		orientationDependent = inherit.orientationDependent;
		generator = (uvw, i, x, y, z) -> {
			inherit.generator.computeUvw(uvw, i, x, y, z);
			processor.processUvw(uvw, i);
		};
	}

	UvType(float[] normal) {
		worldUvs = true;
		orientationDependent = false;
		generator = (uvw, i, scale, _1, _2) -> {
			uvw[i] = scale * normal[0];
			uvw[i + 1] = scale * normal[1];
			uvw[i + 2] = scale * normal[2];
		};
	}

	@FunctionalInterface
	public interface UvGenerator {
		void computeUvw(float[] out, int offset, float x, float y, float z);
	}

	@FunctionalInterface
	public interface UvProcessor {
		void processUvw(float[] out, int offset);
	}

	public void computeModelUvw(float[] out, int offset, float x, float y, float z) {
		assert generator != null : this + " does not support computing UVs";
		generator.computeUvw(out, offset, x, y, z);
	}

	public void computeWorldUvw(float[] out, int offset, float scale) {
		assert generator != null : this + " does not support computing UVs";
		generator.computeUvw(out, offset, scale, 0, 0);
	}

	private static void mirrorDiagonally(float[] uv, int i) {
		if (uv[i] < uv[i + 1]) {
			float temp = uv[i];
			uv[i] = uv[i + 1];
			uv[i + 1] = temp;
		}
	}
}
