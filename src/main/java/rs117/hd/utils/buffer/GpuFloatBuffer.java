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

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;
import rs117.hd.HdPlugin;

public class GpuFloatBuffer
{
	private FloatBuffer buffer;

	public GpuFloatBuffer()
	{
		this(65536);
	}

	public GpuFloatBuffer(int initialCapacity) {
		try {
			buffer = MemoryUtil.memAllocFloat(initialCapacity);
		} catch (OutOfMemoryError oom) {
			// Force garbage collection and try again
			System.gc();
			buffer = MemoryUtil.memAllocFloat(initialCapacity);
		}
	}

	public void destroy() {
		if (buffer != null)
			MemoryUtil.memFree(buffer);
		buffer = null;
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		destroy();
	}

	public void put(float x, float y, float z, float w) {
		buffer.put(x).put(y).put(z).put(w);
	}

	public void put(float x, float y, float z, int w) {
		buffer.put(x).put(y).put(z).put(Float.intBitsToFloat(w));
	}

	public void put(float[] floats) {
		buffer.put(floats);
	}

	public void put(FloatBuffer buffer) {
		this.buffer.put(buffer);
	}

	public int position()
	{
		return buffer.position();
	}

	public void flip() {
		buffer.flip();
	}

	public GpuFloatBuffer clear() {
		buffer.clear();
		return this;
	}

	public int capacity() {
		return buffer.capacity();
	}

	public void ensureCapacity(int size) {
		int capacity = buffer.capacity();
		final int position = buffer.position();
		if ((capacity - position) < size) {
			do {
				capacity = (int) (capacity * HdPlugin.BUFFER_GROWTH_MULTIPLIER);
			}
			while ((capacity - position) < size);

			FloatBuffer newB = MemoryUtil.memAllocFloat(capacity);
			buffer.flip();
			newB.put(buffer);
			MemoryUtil.memFree(buffer);
			buffer = newB;
		}
	}

	public FloatBuffer getBuffer()
	{
		return buffer;
	}
}
