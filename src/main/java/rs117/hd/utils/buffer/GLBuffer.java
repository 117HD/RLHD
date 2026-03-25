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
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT;
import static org.lwjgl.opengl.GL44.glBufferStorage;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.SUPPORTS_STORAGE_BUFFERS;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class GLBuffer implements Destructible {
	private static ByteBuffer COPY_READ_BUFFER, COPY_WRITE_BUFFER;
	public static final boolean DEBUG_MAC_OS = false;

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

	public final int target;
	public final int usage;

	public String name;
	public int storageFlags;

	public int id;
	public long size;

	private GLMappedBuffer mappedBuffer;

	public GLBuffer(String name, int target, int usage, int storageFlags) {
		assert target != GL_ELEMENT_ARRAY_BUFFER || this instanceof EBO;
		this.name = name;
		this.target = target;
		this.usage = usage;
		this.storageFlags = storageFlags;
	}

	public GLBuffer(String name, int target, int usage) {
		this(name, target, usage, STORAGE_NONE);
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

		try {
			count = sortAndMergeRanges(srcOffsetBytes, dstOffsetBytes, numBytes, count);

			if (GL_CAPS.GL_ARB_copy_buffer && !DEBUG_MAC_OS) {
				copyWithCopyBuffer(srcOffsetBytes, dstOffsetBytes, numBytes, count);
			} else if (GL_CAPS.GL_ARB_map_buffer_range && !DEBUG_MAC_OS) {
				copyWithMapBufferRange(srcId, dstId, srcOffsetBytes, dstOffsetBytes, numBytes, count);
			} else {
				copyWithMapBuffer(srcId, dstId, srcOffsetBytes, dstOffsetBytes, numBytes, count);
			}

			if (checkGLErrors()) {
				long srcSizeBytes = glGetBufferParameteri64(GL_COPY_READ_BUFFER, GL_BUFFER_SIZE);
				long dstSizeBytes = glGetBufferParameteri64(GL_COPY_WRITE_BUFFER, GL_BUFFER_SIZE);
				log.error("Errors copying buffers src: {} dst: {} srcSize: {} dstSize: {}", srcId, dstId, srcSizeBytes, dstSizeBytes);
			}
		} finally {
			glBindBuffer(GL_COPY_READ_BUFFER, 0);
			glBindBuffer(GL_COPY_WRITE_BUFFER, 0);
		}
	}

	private static int sortAndMergeRanges(
		long[] src,
		long[] dst,
		long[] size,
		int count
	) {
		if (count <= 1)
			return count;

		// Sort by dst offset
		for (int i = 1; i < count; i++) {
			long dstKey = dst[i];
			long srcKey = src[i];
			long sizeKey = size[i];

			int j = i - 1;
			while (j >= 0 && dst[j] > dstKey) {
				dst[j + 1] = dst[j];
				src[j + 1] = src[j];
				size[j + 1] = size[j];
				j--;
			}

			dst[j + 1] = dstKey;
			src[j + 1] = srcKey;
			size[j + 1] = sizeKey;
		}

		// Merge adjacent ranges after sort
		int write = 0;
		for (int read = 1; read < count; read++) {
			long prevSrc = src[write];
			long prevDst = dst[write];
			long prevSize = size[write];

			long currSrc = src[read];
			long currDst = dst[read];
			long currSize = size[read];

			boolean adjacent =
				(prevDst + prevSize == currDst) &&
				(prevSrc + prevSize == currSrc);

			if (adjacent) {
				size[write] += currSize;
			} else {
				write++;
				src[write] = currSrc;
				dst[write] = currDst;
				size[write] = currSize;
			}
		}

		return write + 1;
	}

	private static void copyWithCopyBuffer(long[] src, long[] dst, long[] size, int count) {
		for (int i = 0; i < count; i++)
			glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, src[i], dst[i], size[i]);
	}

	private static void copyWithMapBufferRange(
		int srcId,
		int dstId,
		long[] srcOffsetBytes,
		long[] dstOffsetBytes,
		long[] numBytes,
		int count
	) {
		long srcMinOffset = Long.MAX_VALUE, dstMinOffset = Long.MAX_VALUE, bytesToMap = 0;
		boolean contiguous = true;

		for (int i = 0; i < count; i++) {
			if (i > 0 && contiguous)
				contiguous = dstOffsetBytes[i - 1] + numBytes[i - 1] == dstOffsetBytes[i];

			srcMinOffset = min(srcMinOffset, srcOffsetBytes[i]);
			dstMinOffset = min(dstMinOffset, dstOffsetBytes[i]);
			bytesToMap = max(bytesToMap, dstOffsetBytes[i] - dstMinOffset + numBytes[i]);
		}

		ByteBuffer src = null, dst = null;
		try {
			src = glMapBufferRange(GL_COPY_READ_BUFFER, srcMinOffset, bytesToMap, GL_MAP_READ_BIT, COPY_READ_BUFFER);
			if (src == null) {
				log.error("Failed to map SRC buffer {}, offset: {}, size: {}", srcId, srcMinOffset, bytesToMap, new Throwable());
				return;
			}
			COPY_READ_BUFFER = src;

			dst = glMapBufferRange(
				GL_COPY_WRITE_BUFFER,
				dstMinOffset,
				bytesToMap,
				GL_MAP_WRITE_BIT | (contiguous ? GL_MAP_INVALIDATE_RANGE_BIT : GL_MAP_FLUSH_EXPLICIT_BIT),
				COPY_WRITE_BUFFER
			);
			if (dst == null) {
				log.error("Failed to map DST buffer {}, offset: {}, size: {}", dstId, dstMinOffset, bytesToMap, new Throwable());
				return;
			}
			COPY_WRITE_BUFFER = dst;

			performCopies(
				src, dst,
				srcMinOffset, dstMinOffset,
				srcOffsetBytes, dstOffsetBytes, numBytes,
				count, !contiguous
			);
		} finally {
			if (src != null) glUnmapBuffer(GL_COPY_READ_BUFFER);
			if (dst != null) glUnmapBuffer(GL_COPY_WRITE_BUFFER);
		}
	}

	private static void copyWithMapBuffer(
		int srcId,
		int dstId,
		long[] srcOffsetBytes,
		long[] dstOffsetBytes,
		long[] numBytes,
		int count
	) {
		ByteBuffer src = null, dst = null;
		try {
			src = glMapBuffer(GL_COPY_READ_BUFFER, GL_READ_ONLY, COPY_READ_BUFFER);
			if (src == null) {
				log.error("Failed to map SRC buffer={}", srcId);
				return;
			}
			COPY_READ_BUFFER = src;

			dst = glMapBuffer(GL_COPY_WRITE_BUFFER, GL_WRITE_ONLY, COPY_WRITE_BUFFER);
			if (dst == null) {
				log.error("Failed to map DST buffer={}", dstId);
				glUnmapBuffer(GL_COPY_READ_BUFFER);
				return;
			}
			COPY_WRITE_BUFFER = dst;

			performCopies(
				src, dst,
				0, 0,
				srcOffsetBytes, dstOffsetBytes, numBytes,
				count, false
			);
		} finally {
			if (src != null) glUnmapBuffer(GL_COPY_READ_BUFFER);
			if (dst != null) glUnmapBuffer(GL_COPY_WRITE_BUFFER);
		}
	}

	private static void performCopies(
		ByteBuffer src,
		ByteBuffer dst,
		long srcBase,
		long dstBase,
		long[] srcOffsets,
		long[] dstOffsets,
		long[] sizes,
		int count,
		boolean flush
	) {
		long flushStart = -1;
		long flushEnd = -1;

		for (int i = 0; i < count; i++) {
			int srcPos = (int) (srcOffsets[i] - srcBase);
			int dstPos = (int) (dstOffsets[i] - dstBase);
			int len = (int) sizes[i];

			// Update the limit before the position, to appease internal bounds checks
			src.limit(srcPos + len);
			src.position(srcPos);

			dst.limit(dstPos + len);
			dst.position(dstPos);

			dst.put(src);

			if (flush) {
				if (flushStart == -1) {
					flushStart = dstPos;
					flushEnd = dstPos + len;
				} else if (flushEnd == dstPos) {
					flushEnd += len;
				} else {
					glFlushMappedBufferRange(GL_COPY_WRITE_BUFFER, flushStart, flushEnd - flushStart);
					flushStart = flushEnd = -1;
				}
			}
		}

		if (flushStart != -1)
			glFlushMappedBufferRange(GL_COPY_WRITE_BUFFER, flushStart, flushEnd - flushStart);
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

	public void setName(String newName) {
		if (newName != null && !newName.equals(name)) {
			name = newName;
			if (id != 0 && log.isDebugEnabled() && GL_CAPS.OpenGL43)
				GL43C.glObjectLabel(GL43C.GL_BUFFER, id, name);
		}
	}

	@Override
	public void destroy() {
		if (mappedBuffer != null)
			mappedBuffer.destroy();
		mappedBuffer = null;

		if (id != 0) {
			glDeleteBuffers(id);
			id = 0;
		}

		size = 0;
	}

	@Override
	public String toString() {
		return String.format("Name: %s, Capacity: %d", name, size);
	}

	@Override
	@SuppressWarnings("deprecation")
	protected void finalize() {
		if (id != 0)
			DestructibleHandler.queueLeakedDestruction(this);
	}

	public void orphan() {
		if (isStorageBuffer())
			throw new IllegalStateException("Not implemented for storage buffers. Perhaps via glInvalidateBufferData?");

		bind();
		glBufferData(target, size, usage);
		unbind();
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
			bind();
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

		final int mappedFlags = mappedBuffer != null && mappedBuffer.isMapped() ? mappedBuffer.getMappedFlags() : 0;
		if (mappedFlags != 0)
			unmap();

		int oldBuffer = id;
		// Create a new buffer if we have to preserve existing data
		if (byteOffset > 0 || (storageFlags & STORAGE_IMMUTABLE) != 0)
			id = glGenBuffers();

		bind();

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

		unbind();

		if (id != oldBuffer && oldBuffer != 0 && byteOffset > 0) {
			// Neither buffer must be mapped before this, except for with the persistent bit
			copyRangeTo(oldBuffer, id, 0, 0, byteOffset);
			glDeleteBuffers(oldBuffer);
		}

		// If was mapped, remap without GL_MAP_INVALIDATE_BUFFER_BIT, since we may have previously written data
		if (mappedFlags != 0 && !isStorageBuffer())
			mappedBuffer.map(mappedFlags);

		return true;
	}

	public boolean isStorageBuffer() {
		return storageFlags != STORAGE_NONE && SUPPORTS_STORAGE_BUFFERS;
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

	public GLMappedBuffer map(int flags, long byteOffset, long byteSize) {
		if (mappedBuffer == null)
			mappedBuffer = new GLMappedBuffer(this);
		ensureCapacity(byteOffset, byteSize);
		return mappedBuffer.map(flags, byteOffset, byteSize);
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

	public static class EBO extends GLBuffer {
		public EBO(String name, int usage) {
			super(name, GL_ELEMENT_ARRAY_BUFFER, usage);
		}

		@Override
		public void bind() {
			glBindVertexArray(0);
			glBindBuffer(target, id);
		}

		@Override
		public void unbind() {
			glBindVertexArray(0);
			glBindBuffer(target, 0);
		}
	}
}
