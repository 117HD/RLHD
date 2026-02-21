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
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.*;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL44.glBufferStorage;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class GLBuffer {
	private static final boolean DEBUG_MAC_OS = false;

	public static int STORAGE_NONE = 0;
	public static int STORAGE_PERSISTENT = 1;
	public static int STORAGE_IMMUTABLE = 2;
	public static int STORAGE_CLIENT = 4;
	public static int STORAGE_READ = 8;
	public static int STORAGE_WRITE = 16;

	public static int MAP_READ = 1;
	public static int MAP_WRITE = 2;
	public static int MAP_UNSYNCHRONIZED = 4;
	public static int MAP_INVALIDATE = 8;

	public final String name;
	public final int target;
	public final int usage;
	public int storageFlags;

	public int id;
	public long size;

	private GLMappedBuffer mappedBuffer;

	public GLBuffer(String name, int target, int usage, int storageFlags) {
		this.name = name;
		this.target = target;
		this.usage = usage;
		this.storageFlags = storageFlags;
	}

	public GLBuffer(String name, int target, int usage) {
		this(name, target, usage, STORAGE_NONE);
	}

	public static boolean supportsStorageBuffers() {
		return GL_CAPS.GL_ARB_buffer_storage && !DEBUG_MAC_OS;
	}

	public GLBuffer initialize() {
		return initialize(0);
	}

	public GLBuffer initialize(long initialCapacity) {
		id = glGenBuffers();
		// Initialize both GL and CL buffers to buffers of a single byte or more,
		// to ensure that valid buffers are given to compute dispatches.
		// This is particularly important on Apple M2 Max, where an uninitialized buffer leads to a crash
		ensureCapacity(max(1, initialCapacity));
		unbind();
		return this;
	}

	public void destroy() {
		if (mappedBuffer != null)
			unmap();

		if (id != 0) {
			glDeleteBuffers(id);
			id = 0;
		}

		size = 0;
	}

	public void orphan() {
		if (isStorageBuffer())
			throw new IllegalStateException("Not implemented for storage buffers. Perhaps via glInvalidateBufferData?");

		glBindBuffer(target, id);
		glBufferData(target, size, usage);
		glBindBuffer(target, 0);
	}

	public void bind() {
		glBindBuffer(target, id);
	}

	public void unbind() {
		glBindBuffer(target, 0);
	}

	public boolean ensureCapacity(long numBytes) {
		return ensureCapacity(0, numBytes);
	}

	public boolean ensureCapacity(long byteOffset, long numBytes) {
		numBytes += byteOffset;
		if (numBytes <= size) {
			glBindBuffer(target, id);
			return false;
		}

		numBytes = HDUtils.ceilPow2(numBytes);
		if (log.isTraceEnabled()) {
			log.trace(
				"{} buffer '{}'\t{}",
				size > 0 ? "Resizing" : "Creating",
				name,
				String.format("%.2f MB -> %.2f MB", size / 1e6, numBytes / 1e6)
			);
		}

		final boolean wasMapped = mappedBuffer != null && mappedBuffer.isMapped();
		if (wasMapped) unmap();

		int oldBuffer = id;
		// Create a new buffer if we have to preserve existing data
		if (byteOffset > 0 || (storageFlags & STORAGE_IMMUTABLE) != 0)
			id = glGenBuffers();

		glBindBuffer(target, id);

		if (isStorageBuffer()) {
			int glStorageFlags = GL_MAP_PERSISTENT_BIT;
			int glMapFlags = GL_MAP_PERSISTENT_BIT | GL_MAP_UNSYNCHRONIZED_BIT;
			if ((storageFlags & STORAGE_IMMUTABLE) == 0)
				glStorageFlags |= GL_DYNAMIC_STORAGE_BIT;
			if ((storageFlags & STORAGE_CLIENT) != 0)
				glStorageFlags |= GL_CLIENT_STORAGE_BIT;
			if ((storageFlags & STORAGE_READ) != 0) {
				glStorageFlags |= GL_MAP_READ_BIT;
				glMapFlags |= GL_MAP_READ_BIT;
			}
			if ((storageFlags & STORAGE_WRITE) != 0) {
				glStorageFlags |= GL_MAP_WRITE_BIT;
				glMapFlags |= GL_MAP_WRITE_BIT;
			}

			glBufferStorage(target, numBytes, glStorageFlags);

			if ((storageFlags & (STORAGE_READ | STORAGE_WRITE)) != 0) {
				ByteBuffer buf = glMapBufferRange(target, 0, numBytes, glMapFlags);
				if (buf != null) {
					mappedBuffer = new GLMappedBuffer(this, buf);
				} else {
					log.warn(
						"Persistent buffer failed to map range {} offset: {} size: {} mapFlags: {}",
						name,
						byteOffset,
						numBytes,
						glMapFlags
					);

					// Recreate buffers to fall back to non-persistent
					glDeleteBuffers(id);
					id = glGenBuffers();
					storageFlags = STORAGE_NONE;
				}
				checkGLErrors();
			}
		}

		if (!isStorageBuffer())
			glBufferData(target, numBytes, usage);

		if (log.isDebugEnabled() && GL_CAPS.OpenGL43) {
			checkGLErrors(() -> String.format(
				"Errors encountered on buffer %s offset: %dl size: %dl mapped: %s isStorage: %s",
				name,
				byteOffset,
				size,
				isMapped(),
				isStorageBuffer()
			));
			GL43C.glObjectLabel(GL43C.GL_BUFFER, id, name);
		}

		size = numBytes;

		if (id != oldBuffer && oldBuffer != 0 && byteOffset > 0) {
			// Neither buffer must be mapped before this, except for with the persistent bit
			copyRangeTo(oldBuffer, id, 0, 0, byteOffset);
			glDeleteBuffers(oldBuffer);
		}

		// If was mapped, remap without GL_MAP_INVALIDATE_BUFFER_BIT, since we may have previously written data
		if (wasMapped && !isStorageBuffer())
			mappedBuffer.remap();

		unbind();
		return true;
	}

	public boolean isStorageBuffer() {
		return storageFlags != STORAGE_NONE && supportsStorageBuffers();
	}

	public boolean isMapped() {
		return mappedBuffer != null && mappedBuffer.isMapped();
	}

	public void upload(ByteBuffer data) {
		upload(data, 0);
	}

	public void upload(ByteBuffer data, long byteOffset) {
		long numBytes = data.remaining();
		ensureCapacity(byteOffset, numBytes);
		if (isStorageBuffer()) {
			mappedBuffer.byteView()
				.position((int) byteOffset)
				.put(data);
		} else {
			bind();
			glBufferSubData(target, byteOffset, data);
		}
		checkGLErrors(() -> String.format(
			"Errors encountered on buffer %s upload (offset: %dl size: %dl) offset: %dl size: %dl mapped: %s isStorage: %s",
			name,
			data.position(),
			data.remaining(),
			byteOffset,
			size,
			isMapped(),
			isStorageBuffer()
		));
	}

	public void upload(IntBuffer data) {
		upload(data, 0);
	}

	public void upload(IntBuffer data, long byteOffset) {
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		if (isStorageBuffer()) {
			mappedBuffer.intView()
				.position((int) (byteOffset / 4))
				.put(data);
		} else {
			bind();
			glBufferSubData(target, byteOffset, data);
		}
		checkGLErrors(() -> String.format(
			"Errors encountered on buffer %s upload (offset: %dl size: %dl) offset: %dl size: %dl mapped: %s isStorage: %s",
			name,
			data.position(),
			data.remaining(),
			byteOffset,
			size,
			isMapped(),
			isStorageBuffer()
		));
	}

	public void upload(FloatBuffer data) {
		upload(data, 0);
	}

	public void upload(FloatBuffer data, long byteOffset) {
		long numBytes = 4L * data.remaining();
		ensureCapacity(byteOffset, numBytes);
		if (isStorageBuffer()) {
			mappedBuffer.floatView()
				.position((int) (byteOffset / 4))
				.put(data);
		} else {
			bind();
			glBufferSubData(target, byteOffset, data);
		}
		checkGLErrors(() -> String.format(
			"Errors encountered on buffer %s upload (offset: %dl size: %dl) offset: %dl size: %dl mapped: %s isStorage: %s",
			name,
			data.position(),
			data.remaining(),
			byteOffset,
			size,
			isMapped(),
			isStorageBuffer()
		));
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

	public GLMappedBuffer map(int flags) {
		return map(flags, 0, size);
	}

	public GLMappedBuffer map(int flags, long byteOffset, long byesSize) {
		if (mappedBuffer == null)
			mappedBuffer = new GLMappedBuffer(this);
		return mappedBuffer.map(flags, byteOffset, byesSize);
	}

	public GLMappedBuffer mapped() {
		return mappedBuffer;
	}

	public void unmap() {
		if (mappedBuffer != null)
			mappedBuffer.unmap();
	}

	public void copyTo(GLBuffer dst, long srcOffsetBytes, long dstOffsetBytes, long numBytes) {
		if (numBytes <= 0)
			return;

		dst.ensureCapacity(dstOffsetBytes + numBytes);
		copyRangeTo(this, dst, srcOffsetBytes, dstOffsetBytes, numBytes);
	}

	public void copyMultiTo(GLBuffer dst, long[] srcOffsetBytes, long[] dstOffsetBytes, long[] numBytes, int count) {
		long totalNumBytes = 0;
		for (int i = 0; i < count; i++)
			totalNumBytes = max(dstOffsetBytes[i] + numBytes[i], 0L);
		if (totalNumBytes <= 0)
			return;

		dst.ensureCapacity(totalNumBytes);
		copyRangesTo(this, dst, srcOffsetBytes, dstOffsetBytes, numBytes, count);
	}

	private static void copyRangeTo(int src, int dst, long srcOffsetBytes, long dstOffsetBytes, long numBytes) {
		copyRangesTo(src, dst, new long[] { srcOffsetBytes }, new long[] { dstOffsetBytes }, new long[] { numBytes }, 1);
	}

	private static void copyRangeTo(GLBuffer src, GLBuffer dst, long srcOffsetBytes, long dstOffsetBytes, long numBytes) {
		copyRangesTo(src, dst, new long[] { srcOffsetBytes }, new long[] { dstOffsetBytes }, new long[] { numBytes }, 1);
	}

	private static void copyRangesTo(
		GLBuffer src,
		GLBuffer dst,
		long[] srcOffsetBytes,
		long[] dstOffsetBytes,
		long[] numBytes,
		int count
	) {
		assert !src.isMapped() || src.isStorageBuffer();
		assert !dst.isMapped() || dst.isStorageBuffer();
		copyRangesTo(src.id, dst.id, srcOffsetBytes, dstOffsetBytes, numBytes, count);
	}

	private static void copyRangesTo(
		int srcId,
		int dstId,
		long[] srcOffsetBytes,
		long[] dstOffsetBytes,
		long[] numBytes,
		int count
	) {
		assert count > 0;
		glBindBuffer(GL_COPY_READ_BUFFER, srcId);
		glBindBuffer(GL_COPY_WRITE_BUFFER, dstId);

		if (GL_CAPS.GL_ARB_copy_buffer && !DEBUG_MAC_OS) {
			for (int i = 0; i < count; i++)
				glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, srcOffsetBytes[i], dstOffsetBytes[i], numBytes[i]);
		} else {
			// Fallback path for macOS, which of course has to not support this...
			// This assumes neither of the buffers are already mapped
			assert !supportsStorageBuffers();
			long srcOffset = Long.MAX_VALUE;
			long dstOffset = Long.MAX_VALUE;
			long mapSize = 0;
			for (int i = 0; i < count; i++) {
				srcOffset = min(srcOffset, srcOffsetBytes[i]);
				dstOffset = min(dstOffset, dstOffsetBytes[i]);
				mapSize = max(mapSize, srcOffsetBytes[i] - srcOffset + numBytes[i]);
			}

			ByteBuffer src = null;
			ByteBuffer dst = null;
			try {
				src = glMapBufferRange(GL_COPY_READ_BUFFER, srcOffset, mapSize, GL_MAP_READ_BIT);
				if (src == null) {
					log.error("Failed to map SRC buffer {}, offset: {}, size: {}", srcId, srcOffset, mapSize, new Throwable());
					return;
				}

				dst = glMapBufferRange(GL_COPY_WRITE_BUFFER, dstOffset, mapSize, GL_MAP_WRITE_BIT);
				if (dst == null) {
					log.error("Failed to map DST buffer {}, offset: {}, size: {}", dstId, dstOffset, mapSize, new Throwable());
					return;
				}

				for (int i = 0; i < count; i++) {
					final int srcPos = (int) (srcOffsetBytes[i] - srcOffset);
					final int dstPos = (int) (dstOffsetBytes[i] - dstOffset);
					final int len = (int) numBytes[i];

					try {
						src.position(srcPos);
						src.limit(srcPos + len);

						dst.position(dstPos);
						dst.put(src);
					} catch (Throwable t) {
						log.error("Failed to copy buffer range {} -> {} offset: {} size: {}", srcId, dstId, srcOffsetBytes[i], numBytes[i], t);
					}
				}
			} finally {
				if (src != null)
					glUnmapBuffer(GL_COPY_READ_BUFFER);
				if (dst != null)
					glUnmapBuffer(GL_COPY_WRITE_BUFFER);
			}
		}

		if (checkGLErrors()) {
			long srcSizeBytes = glGetBufferParameteri64(GL_COPY_READ_BUFFER, GL_BUFFER_SIZE);
			long dstSizeBytes = glGetBufferParameteri64(GL_COPY_WRITE_BUFFER, GL_BUFFER_SIZE);
			log.error("Errors copying buffers src: {} dst: {} srcSize: {} dstSize: {}", srcId, dstId, srcSizeBytes, dstSizeBytes);
		}

		glBindBuffer(GL_COPY_READ_BUFFER, 0);
		glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
	}

	public static String storageFlagsToString(int mask) {
		if (mask == STORAGE_NONE) return "STORAGE_NONE";

		StringBuilder sb = new StringBuilder();
		if ((mask & STORAGE_PERSISTENT) != 0) sb.append("STORAGE_PERSISTENT | ");
		if ((mask & STORAGE_IMMUTABLE) != 0) sb.append("STORAGE_IMMUTABLE | ");
		if ((mask & STORAGE_CLIENT) != 0) sb.append("STORAGE_CLIENT | ");
		if ((mask & STORAGE_READ) != 0) sb.append("STORAGE_READ | ");
		if ((mask & STORAGE_WRITE) != 0) sb.append("STORAGE_WRITE | ");

		if (sb.length() > 3)
			sb.setLength(sb.length() - 3);

		return sb.toString();
	}
}
