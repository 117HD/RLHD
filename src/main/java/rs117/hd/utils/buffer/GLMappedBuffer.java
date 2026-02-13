package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.Getter;

import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glMapBuffer;
import static org.lwjgl.opengl.GL15.glUnmapBuffer;
import static org.lwjgl.opengl.GL30.GL_MAP_FLUSH_EXPLICIT_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_INVALIDATE_BUFFER_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_INVALIDATE_RANGE_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_UNSYNCHRONIZED_BIT;
import static org.lwjgl.opengl.GL30.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30.glFlushMappedBufferRange;
import static org.lwjgl.opengl.GL30.glMapBufferRange;
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
	private ByteBuffer mappedBuffer;

	@Getter
	private IntBuffer mappedIntBuffer;

	@Getter
	private FloatBuffer mappedFloatBuffer;

	@Getter
	private boolean mapped;

	@Getter
	private int mappedFlags;

	@Getter
	private long mappedOffset;

	GLMappedBuffer(GLBuffer owner) {
		this.owner = owner;
	}

	GLMappedBuffer(GLBuffer owner, ByteBuffer mappedBuffer) {
		this.owner = owner;
		this.mappedBuffer = mappedBuffer;
		this.mappedIntBuffer = mappedBuffer.asIntBuffer();
		this.mappedFloatBuffer = mappedBuffer.asFloatBuffer();
		this.mapped = true;
	}

	public GLMappedBuffer remap() {
		if (mapped)
			return this;
		return map(mappedFlags);
	}

	public GLMappedBuffer map(int flags) {
		return map(flags, 0, owner.size);
	}

	public GLMappedBuffer map(int flags, long offsetBytes, long sizeBytes) {
		if (mappedBuffer != null) {
			mappedBuffer.position(0);
			mappedIntBuffer.position(0);
			mappedFloatBuffer.position(0);
		}

		if (mapped || owner.isStorageBuffer())
			return this;

		glBindBuffer(owner.target, owner.id);

		int glFlags = 0;
		if ((flags & MAP_WRITE) != 0) glFlags |= GL_MAP_WRITE_BIT;
		if ((flags & MAP_READ) != 0) glFlags |= GL_MAP_READ_BIT;
		if ((flags & MAP_UNSYNCHRONIZED) != 0) glFlags |= GL_MAP_UNSYNCHRONIZED_BIT;

		final ByteBuffer buf;
		if (owner.target != GL_STATIC_DRAW) {
			long mapSize = max(0, min(owner.size - offsetBytes, sizeBytes));
			if (mapSize <= 0) {
				return this;
			}

			if ((flags & MAP_INVALIDATE) != 0) {
				if (mapSize == owner.size) glFlags |= GL_MAP_INVALIDATE_BUFFER_BIT;
				else glFlags |= GL_MAP_INVALIDATE_RANGE_BIT;
			}
			buf = glMapBufferRange(
				owner.target,
				offsetBytes,
				mapSize,
				glFlags | GL_MAP_FLUSH_EXPLICIT_BIT,
				mappedBuffer
			);
			this.mappedOffset = offsetBytes;
		} else {
			buf = glMapBuffer(owner.target, glFlags, mappedBuffer);
			this.mappedOffset = 0;
		}

		if (buf == null)
			throw new RuntimeException("Failed to map buffer " + owner.id);

		if (mappedBuffer != buf) {
			mappedBuffer = buf;
			mappedIntBuffer = buf.asIntBuffer();
			mappedFloatBuffer = buf.asFloatBuffer();
		} else {
			mappedBuffer.position(0);
			mappedIntBuffer.position(0);
			mappedFloatBuffer.position(0);
		}
		this.mappedFlags = flags;
		mapped = true;

		glBindBuffer(owner.target, 0);
		checkGLErrors(() -> "Mapping Buffer: " + owner.name + " Size: " + owner.size);
		return this;
	}

	public int getPositionBytes() {
		int bytesPos = mappedBuffer.position();
		if (mappedIntBuffer.position() * 4 > bytesPos)
			bytesPos = mappedIntBuffer.position() * 4;
		if (mappedFloatBuffer.position() * 4 > bytesPos)
			bytesPos = mappedFloatBuffer.position() * 4;
		return bytesPos;
	}

	public void setPositionBytes(int bytesPos) {
		mappedBuffer.position(bytesPos);
		mappedIntBuffer.position(bytesPos / 4);
		mappedFloatBuffer.position(bytesPos / 4);
	}

	public void syncViews() {
		int bytesPos = getPositionBytes();
		mappedBuffer.position(bytesPos);
		mappedIntBuffer.position(bytesPos / 4);
		mappedFloatBuffer.position(bytesPos / 4);
	}

	public void unmap() {
		if (!mapped || owner.isStorageBuffer())
			return;

		syncViews();

		glBindBuffer(owner.target, owner.id);
		if (owner.target != GL_STATIC_DRAW) {
			mappedBuffer.flip();
			glFlushMappedBufferRange(owner.target, mappedBuffer.position(), mappedBuffer.remaining());
			mappedBuffer.clear();
		}
		glUnmapBuffer(owner.target);
		glBindBuffer(owner.target, 0);

		mapped = false;
	}
}
