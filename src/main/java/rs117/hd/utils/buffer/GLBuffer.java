/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
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

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.checkGLErrors;

@Slf4j
@RequiredArgsConstructor
public class GLBuffer
{
	public final String name;
	public final int target;
	public final int usage;

	public final GLMappedBuffer mapped = new GLMappedBuffer(this);

	public int id;
	public long size;

	public void initialize() {
		initialize(0);
	}

	public void initialize(long initialCapacity) {
		id = glGenBuffers();
		// Initialize both GL and CL buffers to buffers of a single byte or more,
		// to ensure that valid buffers are given to compute dispatches.
		// This is particularly important on Apple M2 Max, where an uninitialized buffer leads to a crash
		ensureCapacity(Math.max(1, initialCapacity));
	}

	public void destroy() {
		size = 0;

		if (id != 0) {
			glDeleteBuffers(id);
			id = 0;
		}
	}

	public GLMappedBuffer map(AccessFlags accessFlags, GLMappedType bufferType) {
		return map(accessFlags, 0, bufferType);
	}

	public GLMappedBuffer map(AccessFlags accessFlags, long bytesOffset, GLMappedType bufferType) {
		if (mapped.isMapped() && bytesOffset != mapped.bytesOffset) {
			unmap(true);
		}

		if (!mapped.isMapped()) {
			glBindBuffer(target, id);
			ByteBuffer buf = glMapBufferRange(target, bytesOffset, size - bytesOffset, accessFlags.flags);
			if (buf != null) {
				mapped.accessFlags = accessFlags;
				mapped.bufferType = bufferType;
				mapped.bytesOffset = bytesOffset;
				mapped.setMappedBuffer(buf);
			}
			HdPlugin.checkGLErrors();
		}

		return mapped;
	}

	public void unmap(boolean flush) {
		if (mapped.isMapped()) {
			glBindBuffer(target, id);
			if (flush) {
				glFlushMappedBufferRange(target, mapped.bytesOffset, mapped.writtenBytes);
				mapped.writtenBytes = 0;
			}
			glUnmapBuffer(target);
			mapped.buffer = null;
			mapped.bufferInt = null;
			mapped.bufferFloat = null;
			glBindBuffer(target, 0);
			HdPlugin.checkGLErrors();
		}
	}

	public void ensureCapacity(long numBytes) {
		ensureCapacity(0, numBytes);
	}

	public void ensureCapacity(long byteOffset, long numBytes) {
		long newSize = byteOffset + numBytes;
		if (newSize <= size) {
			glBindBuffer(target, id);
			return;
		}

		newSize = HDUtils.ceilPow2(newSize);
		if (log.isDebugEnabled() && newSize > 1e6)
			log.debug("Resizing buffer '{}'\t{}", name, String.format("%.2f MB -> %.2f MB", size / 1e6, newSize / 1e6));

		boolean shouldRemap = false;
		if (mapped.isMapped()) {
			shouldRemap = true;
			unmap(false);
		}

		if (byteOffset > 0) {
			// Create a new buffer and copy the old data to it
			int oldBuffer = id;
			id = glGenBuffers();
			glBindBuffer(target, id);
			glBufferData(target, newSize, usage);

			glBindBuffer(GL_COPY_READ_BUFFER, oldBuffer);
			glCopyBufferSubData(GL_COPY_READ_BUFFER, target, 0, 0, byteOffset);
			glDeleteBuffers(oldBuffer);
		} else {
			glBindBuffer(target, id);
			glBufferData(target, newSize, usage);
		}

		size = newSize;

		if (shouldRemap) {
			map(mapped.accessFlags, mapped.bytesOffset, mapped.bufferType);
		}

		if (log.isDebugEnabled() && HdPlugin.GL_CAPS.OpenGL43) {
			GL43C.glObjectLabel(GL43C.GL_BUFFER, id, name);
			checkGLErrors();
		}
	}

	public void upload(ByteBuffer data) {
		upload(data, 0);
	}

	public void upload(ByteBuffer data, long byteOffset) {
		unmap(true);
		long numBytes = data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(IntBuffer data) {
		upload(data, 0);
	}

	public void upload(IntBuffer data, long byteOffset) {
		unmap(true);
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(FloatBuffer data) {
		upload(data, 0);
	}

	public void upload(FloatBuffer data, long byteOffset) {
		unmap(true);
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		glBufferSubData(target, byteOffset, data);
	}

	public void upload(GpuIntBuffer data) {
		upload(data.getBuffer());
	}

	public void upload(GpuIntBuffer data, long byteOffset) {
		upload(data.getBuffer(), byteOffset);
	}

	public void upload(GpuFloatBuffer data) {
		upload(data.getBuffer());
	}

	public void upload(GpuFloatBuffer data, long byteOffset) {
		upload(data.getBuffer(), byteOffset);
	}

	@RequiredArgsConstructor
	public enum AccessFlags {
		Write(GL_MAP_WRITE_BIT | GL_MAP_FLUSH_EXPLICIT_BIT),
		Read(GL_MAP_READ_BIT),
		ReadWrite(GL_MAP_READ_BIT | GL_MAP_WRITE_BIT | GL_MAP_FLUSH_EXPLICIT_BIT),

		UnsafeWrite(Write.flags | GL_MAP_UNSYNCHRONIZED_BIT),
		UnsafeRead(Read.flags | GL_MAP_UNSYNCHRONIZED_BIT),
		UnsafeReadWrite(ReadWrite.flags | GL_MAP_UNSYNCHRONIZED_BIT);;

		public final int flags;
	}

	@RequiredArgsConstructor
	public enum GLMappedType {
		BYTE(1),
		INT(4),
		FLOAT(4);

		public final int stride;
	}

	@RequiredArgsConstructor
	public static final class GLMappedBuffer {
		private final GLBuffer owner;

		private AccessFlags accessFlags;
		private GLMappedType bufferType;

		private ByteBuffer buffer;
		private IntBuffer bufferInt;
		private FloatBuffer bufferFloat;

		private long bytesOffset;
		private long writtenBytes;

		public boolean isMapped() {
			return buffer != null;
		}

		private void setMappedBuffer(ByteBuffer buffer) {
			this.buffer = buffer;

			if (bufferType == GLMappedType.INT) {
				bufferInt = buffer.asIntBuffer();
				bufferInt.position((int) (writtenBytes / 4));
			} else if (bufferType == GLMappedType.FLOAT) {
				bufferFloat = buffer.asFloatBuffer();
				bufferFloat.position((int) (writtenBytes / 4));
			}
		}

		public GLMappedBuffer ensureCapacity(int size) {
			owner.ensureCapacity(bytesOffset + writtenBytes + (size * (long) bufferType.stride));
			return this;
		}

		public GLMappedBuffer put(int value) {
			bufferInt.put(value);
			writtenBytes += Integer.BYTES;
			return this;
		}

		public GLMappedBuffer put(float value) {
			bufferFloat.put(value);
			writtenBytes += Float.BYTES;
			return this;
		}

		public GLMappedBuffer put(int x, int y, int z, int w) {
			bufferInt.put(x);
			bufferInt.put(y);
			bufferInt.put(z);
			bufferInt.put(w);
			writtenBytes += 4 * Integer.BYTES;
			return this;
		}

		public GLMappedBuffer put(float x, float y, float z, float w) {
			bufferFloat.put(x);
			bufferFloat.put(y);
			bufferFloat.put(z);
			bufferFloat.put(w);
			writtenBytes += 4 * Float.BYTES;
			return this;
		}

		public GLMappedBuffer put(float x, float y, float z, int w) {
			bufferFloat.put(x);
			bufferFloat.put(y);
			bufferFloat.put(z);
			bufferFloat.put(Float.intBitsToFloat(w));
			writtenBytes += 4 * Float.BYTES;
			return this;
		}

		public GLMappedBuffer put(int[] array) {
			bufferInt.put(array);
			writtenBytes += (long) array.length * Integer.BYTES;
			return this;
		}

		public GLMappedBuffer put(float[] array) {
			bufferFloat.put(array);
			writtenBytes += (long) array.length * Float.BYTES;
			return this;
		}

		public GLMappedBuffer put(IntBuffer inBuffer) {
			writtenBytes += (long) bufferInt.remaining() * Integer.BYTES;
			bufferInt.put(inBuffer);
			return this;
		}

		public void put(FloatBuffer inBuffer) {
			writtenBytes += (long) bufferFloat.remaining() * Float.BYTES;
			bufferFloat.put(inBuffer);
		}
	}
}