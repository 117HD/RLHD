package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.Getter;
import lombok.experimental.Accessors;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.checkGLErrors;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.buffer.GLBuffer.MAP_INVALIDATE;
import static rs117.hd.utils.buffer.GLBuffer.MAP_READ;
import static rs117.hd.utils.buffer.GLBuffer.MAP_UNSYNCHRONIZED;
import static rs117.hd.utils.buffer.GLBuffer.MAP_WRITE;

public final class GLMappedBuffer {
	@Getter
	private final GLBuffer owner;

	@Getter
	@Accessors(fluent = true)
	private ByteBuffer byteView;

	@Getter
	@Accessors(fluent = true)
	private IntBuffer intView;

	@Getter
	@Accessors(fluent = true)
	private FloatBuffer floatView;

	@Getter
	private boolean mapped;

	@Getter
	private int mappedFlags;

	@Getter
	private long mappingOffset; // the currently mapped slice's offset into the backing GPU buffer
	private ByteBuffer mappingBuffer; // the currently mapped slice of the backing GPU buffer

	private long absoluteViewOffset; // the current offset for the view buffers, into the backing GPU buffer

	GLMappedBuffer(GLBuffer owner) {
		this.owner = owner;
	}

	GLMappedBuffer(GLBuffer owner, ByteBuffer mappedBuffer) {
		this.owner = owner;
		this.mapped = true;
		updateViews(mappedBuffer, 0, mappedBuffer.capacity());
	}

	public GLMappedBuffer map(int flags) {
		return map(flags, 0, owner.size);
	}

	public GLMappedBuffer map(int flags, long offsetBytes, long sizeBytes) {
		if (mapped || owner.isStorageBuffer()) {
			updateViews(mappingBuffer, offsetBytes, sizeBytes);
			return this;
		}

		owner.bind();

		final ByteBuffer buf;
		if (owner.target != GL_STATIC_DRAW && GL_CAPS.GL_ARB_map_buffer_range && !GLBuffer.DEBUG_MAC_OS) {
			// Map a part of the buffer or its entirety depending on the offset and size
			int glFlags = 0;
			if ((flags & MAP_WRITE) != 0) glFlags |= GL_MAP_WRITE_BIT;
			if ((flags & MAP_READ) != 0) glFlags |= GL_MAP_READ_BIT;
			if ((flags & MAP_UNSYNCHRONIZED) != 0) glFlags |= GL_MAP_UNSYNCHRONIZED_BIT;

			long mapSize = clamp(owner.size - offsetBytes, 0, sizeBytes);
			if (mapSize <= 0)
				return this;

			if ((flags & MAP_INVALIDATE) != 0) {
				if (mapSize == owner.size) glFlags |= GL_MAP_INVALIDATE_BUFFER_BIT;
				else glFlags |= GL_MAP_INVALIDATE_RANGE_BIT;
			}
			buf = glMapBufferRange(
				owner.target,
				offsetBytes,
				mapSize,
				glFlags | GL_MAP_FLUSH_EXPLICIT_BIT,
				mappingBuffer
			);
			this.mappingOffset = offsetBytes;
		} else {
			// Map the whole buffer due to lack of GL_ARB_map_buffer_range support
			int glAccess;
			if ((flags & (MAP_WRITE | MAP_READ)) == (MAP_WRITE | MAP_READ)) {
				glAccess = GL_READ_WRITE;
			} else if ((flags & MAP_WRITE) != 0) {
				glAccess = GL_WRITE_ONLY;
			} else {
				glAccess = GL_READ_ONLY;
			}

			buf = glMapBuffer(owner.target, glAccess, mappingBuffer);
			this.mappingOffset = 0;
		}

		if (buf == null)
			throw new RuntimeException("Failed to map buffer " + owner.id);

		updateViews(buf, offsetBytes, sizeBytes);

		this.mappedFlags = flags;
		mapped = true;

		owner.unbind();
		checkGLErrors(() -> "Mapping Buffer: " + owner.name + " Size: " + owner.size);
		return this;
	}

	private void updateViews(ByteBuffer buffer, long offsetBytes, long sizeBytes) {
		int size = Math.toIntExact(sizeBytes);
		if (mappingBuffer != buffer || absoluteViewOffset != offsetBytes || byteView.capacity() != size) {
			assert offsetBytes >= mappingOffset : "The view must be within the mapped slice of the backing buffer";
			absoluteViewOffset = offsetBytes;
			int mappedOffset = Math.toIntExact(absoluteViewOffset - mappingOffset);
			mappingBuffer = buffer
				.clear()
				.position(mappedOffset)
				.limit(Math.addExact(mappedOffset, size));
			byteView = mappingBuffer.slice().order(mappingBuffer.order());
			intView = byteView.asIntBuffer();
			floatView = byteView.asFloatBuffer();
		} else {
			byteView.clear();
			intView.clear();
			floatView.clear();
		}
	}

	public int getPositionBytes() {
		int bytesPos = byteView.position();
		if (intView.position() * 4 > bytesPos)
			bytesPos = intView.position() * 4;
		if (floatView.position() * 4 > bytesPos)
			bytesPos = floatView.position() * 4;
		return bytesPos;
	}

	public void setPositionBytes(int bytesPos) {
		if (byteView == null)
			return;
		byteView.position(bytesPos);
		intView.position(bytesPos / 4);
		floatView.position(bytesPos / 4);
	}

	public void syncViews() {
		int bytesPos = getPositionBytes();
		byteView.position(bytesPos);
		intView.position(bytesPos / 4);
		floatView.position(bytesPos / 4);
	}

	public void unmap() {
		if (!mapped || owner.isStorageBuffer())
			return;

		syncViews();

		owner.bind();
		if (owner.target != GL_STATIC_DRAW && GL_CAPS.GL_ARB_map_buffer_range && !GLBuffer.DEBUG_MAC_OS) {
			byteView.flip();
			glFlushMappedBufferRange(owner.target, absoluteViewOffset - mappingOffset, byteView.remaining());
			byteView.clear();
		}
		glUnmapBuffer(owner.target);
		owner.unbind();

		mapped = false;
	}

	void destroy() {
		unmap();

		mappingBuffer = null;
		byteView = null;
		intView = null;
		floatView = null;
	}
}
