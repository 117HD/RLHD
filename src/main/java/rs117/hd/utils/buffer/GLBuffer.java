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

import java.nio.Buffer;
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
		if (!mapped.isMapped()) {
			glBindBuffer(target, id);
			ByteBuffer buf = glMapBufferRange(target, bytesOffset, size - bytesOffset, accessFlags.glMapBufferRangeFlags);
			if (buf != null) {
				mapped.buffer = buf;
				mapped.bufferInt = bufferType == GLMappedType.INT ? mapped.buffer.asIntBuffer() : null;
				mapped.bufferFloat = bufferType == GLMappedType.FLOAT ? mapped.buffer.asFloatBuffer() : null;
				mapped.accessFlags = accessFlags;
				mapped.bufferType = bufferType;
				mapped.bytesOffset = bytesOffset;
			}
			HdPlugin.checkGLErrors();
		}

		return mapped;
	}

	public void unmap(boolean flush) {
		if (mapped.isMapped()) {
			glBindBuffer(target, id);
			if (flush) {
				glFlushMappedBufferRange(target, mapped.bytesOffset, mapped.buffer.position());
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

		int mappedBufferByteOffset = -1;
		if (mapped.isMapped()) {
			mappedBufferByteOffset = mapped.position() * mapped.bufferType.stride;
			mapped.buffer.position(mappedBufferByteOffset);
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

		if (mappedBufferByteOffset != -1) {
			map(mapped.accessFlags, mapped.bytesOffset, mapped.bufferType);
			mapped.setPositionInBytes(mappedBufferByteOffset);
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
		ReadWrite(GL_MAP_READ_BIT | GL_MAP_WRITE_BIT | GL_MAP_FLUSH_EXPLICIT_BIT);

		public final int glMapBufferRangeFlags;
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
		private long bytesOffset;

		private ByteBuffer buffer;
		private IntBuffer bufferInt;
		private FloatBuffer bufferFloat;

		public boolean isMapped() {
			return buffer != null;
		}

		public <T extends Buffer> T getBuffer() {
			if (buffer == null) {
				return null;
			}

			switch (bufferType) {
				case BYTE:
					return (T) buffer;
				case INT:
					return (T) bufferInt;
				case FLOAT:
					return (T) bufferFloat;
				default:
					throw new IllegalStateException("Unexpected value: " + bufferType);
			}
		}

		public int position() {
			Buffer buf = getBuffer();
			if (buf != null) {
				return buf.position();
			}
			return 0;
		}

		public void setPositionInBytes(int bytesOffset) {
			if (buffer != null) buffer.position(bytesOffset);
			if (bufferInt != null) bufferInt.position(bytesOffset / 4);
			if (bufferFloat != null) bufferFloat.position(bytesOffset / 4);
		}

		public GLMappedBuffer ensureCapacity(int size) {
			long numBytes = bytesOffset + ((position() + size) * (long) bufferType.stride);
			if (numBytes > owner.size) {
				owner.ensureCapacity(numBytes);
			}
			return this;
		}

		public GLMappedBuffer put(int value) {
			if (bufferInt != null) {
				bufferInt.put(value);
			}
			return this;
		}

		public GLMappedBuffer put(float value) {
			if (bufferFloat != null) {
				bufferFloat.put(value);
			}
			return this;
		}

		public GLMappedBuffer put(int x, int y, int z, int w) {
			if (bufferInt != null) {
				bufferInt.put(x);
				bufferInt.put(y);
				bufferInt.put(z);
				bufferInt.put(w);
			}
			return this;
		}

		public GLMappedBuffer put(float x, float y, float z, float w) {
			if (bufferFloat != null) {
				bufferFloat.put(x);
				bufferFloat.put(y);
				bufferFloat.put(z);
				bufferFloat.put(w);
			}
			return this;
		}

		public GLMappedBuffer put(float x, float y, float z, int w) {
			if (bufferFloat != null) {
				bufferFloat.put(x);
				bufferFloat.put(y);
				bufferFloat.put(z);
				bufferFloat.put(Float.intBitsToFloat(w));
			}
			return this;
		}

		public GLMappedBuffer put(int[] array) {
			if (bufferInt != null && array != null) {
				bufferInt.put(array);
			}
			return this;
		}

		public GLMappedBuffer put(float[] array) {
			if (bufferFloat != null && array != null) {
				bufferFloat.put(array);
			}
			return this;
		}

		public GLMappedBuffer put(IntBuffer inBuffer) {
			if (bufferInt != null) {
				bufferInt.put(inBuffer);
			}
			return this;
		}

		public void put(FloatBuffer inBuffer) {
			if (bufferFloat != null) {
				bufferFloat.put(inBuffer);
			}
		}
	}
}