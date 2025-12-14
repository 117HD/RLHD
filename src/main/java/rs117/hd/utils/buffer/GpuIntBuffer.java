/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package rs117.hd.utils.buffer;

import java.nio.IntBuffer;
import lombok.Getter;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;

import static rs117.hd.utils.MathUtils.*;

public class GpuIntBuffer
{
	@Getter
	private IntBuffer buffer;
	private final boolean ownsBuffer;

	public GpuIntBuffer()
	{
		this(65536);
	}

	public GpuIntBuffer(int initialCapacity) {
		try {
			buffer = MemoryUtil.memAllocInt(initialCapacity);
		} catch (OutOfMemoryError oom) {
			// Force garbage collection and try again
			System.gc();
			buffer = MemoryUtil.memAllocInt(initialCapacity);
		}
		ownsBuffer = true;
	}

	public GpuIntBuffer(IntBuffer buffer) {
		this.buffer = buffer;
		ownsBuffer = false;
	}

	public void destroy() {
		if (buffer != null && ownsBuffer)
			MemoryUtil.memFree(buffer);
		buffer = null;
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		destroy();
	}

	@Override
	public String toString() {
		return String.format(
			"%s@%x(pos=%d, size=%d)",
			getClass().getSimpleName(),
			hashCode(),
			buffer.position(),
			buffer.capacity()
		);
	}

	public void put(int x, int y, int z) {
		buffer.put(x).put(y).put(z);
	}

	public void put(float x, float y, float z, int w) {
		buffer
			.put(Float.floatToIntBits(x))
			.put(Float.floatToIntBits(y))
			.put(Float.floatToIntBits(z))
			.put(w);
	}

	public void put(int[] ints) {
		buffer.put(ints);
	}

	public void put(IntBuffer buffer) {
		this.buffer.put(buffer);
	}

	public void putVertex(
		int x, int y, int z,
		int u, int v, int w,
		int nx, int ny, int nz
	) {
		buffer.put((y & 0xFFFF) << 16 | x & 0xFFFF);
		buffer.put((u & 0xFFFF) << 16 | z & 0xFFFF);
		buffer.put((w & 0xFFFF) << 16 | v & 0xFFFF);
		buffer.put((ny & 0xFFFF) << 16 | nx & 0xFFFF);
		buffer.put(nz & 0xFFFF);
	}

	public static int normShort(float f) {
		return round(clamp(f, -1, 1) * Short.MAX_VALUE);
	}

	public void putVertex(
		int x, int y, int z,
		float u, float v, float w,
		float nx, float ny, float nz
	) {
		buffer.put((y & 0xFFFF) << 16 | x & 0xFFFF);
		buffer.put(float16(u) << 16 | z & 0xFFFF);
		buffer.put(float16(w) << 16 | float16(v));
		// This only works with normalized normals
		buffer.put((normShort(ny) & 0xFFFF) << 16 | (normShort(nx) & 0xFFFF));
		buffer.put((normShort(nz) & 0xFFFF));
	}

	public int putFace(
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC,
		int terrainDataA,  int terrainDataB,  int terrainDataC ) {
		final int textureFaceIdx = buffer.position() / 3;
		buffer.put(alphaBiasHslA);
		buffer.put(alphaBiasHslB);
		buffer.put(alphaBiasHslC);

		buffer.put(materialDataA);
		buffer.put(materialDataB);
		buffer.put(materialDataC);

		buffer.put(terrainDataA);
		buffer.put(terrainDataB);
		buffer.put(terrainDataC);
		return textureFaceIdx;
	}

	public void putVertex(
		int x, int y, int z,
		float u, float v, int textureFaceIdx,
		int nx, int ny, int nz
	) {
		buffer.put((y & 0xFFFF) << 16 | x & 0xFFFF);
		buffer.put(float16(u) << 16 | z & 0xFFFF);
		buffer.put(textureFaceIdx << 16 | float16(v));
		// Unnormalized normals, assumed to be within short max
		buffer.put((ny & 0xFFFF) << 16 | nx & 0xFFFF);
		buffer.put(nz & 0xFFFF);
	}

	public static int putFace(
		IntBuffer buffer,
		int alphaBiasHslA, int alphaBiasHslB, int alphaBiasHslC,
		int materialDataA, int materialDataB, int materialDataC) {
		final int textureFaceIdx = buffer.position() / 3;
		buffer.put(alphaBiasHslA);
		buffer.put(alphaBiasHslB);
		buffer.put(alphaBiasHslC);

		buffer.put(materialDataA);
		buffer.put(materialDataB);
		buffer.put(materialDataC);

		buffer.put(0); // TODO: Remove?
		buffer.put(0);
		buffer.put(0);
		return textureFaceIdx;
	}

	public static void putFloatVertex(
		IntBuffer buffer,
		float x, float y, float z,
		float u, float v, int textureFaceIdx,
		int nx, int ny, int nz
	) {
		buffer.put(Float.floatToRawIntBits(x));
		buffer.put(Float.floatToRawIntBits(y));
		buffer.put(Float.floatToRawIntBits(z));
		buffer.put(float16(v) << 16 | float16(u));
		buffer.put((nx & 0xFFFF) << 16 | textureFaceIdx);
		buffer.put((nz & 0xFFFF) << 16 | ny & 0xFFFF);
	}

	public int position()
	{
		return buffer.position();
	}

	public void flip() {
		buffer.flip();
	}

	public GpuIntBuffer clear() {
		buffer.clear();
		return this;
	}

	public int capacity() {
		return buffer.capacity();
	}

	public GpuIntBuffer ensureCapacity(int size) {
		int capacity = buffer.capacity();
		final int position = buffer.position();
		if ((capacity - position) < size) {
			do {
				capacity = (int) (capacity * HdPlugin.BUFFER_GROWTH_MULTIPLIER);
			}
			while ((capacity - position) < size);

			IntBuffer newB = MemoryUtil.memAllocInt(capacity);
			buffer.flip();
			newB.put(buffer);
			MemoryUtil.memFree(buffer);
			buffer = newB;
		}

		return this;
	}
}
