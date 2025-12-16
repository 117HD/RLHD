package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import lombok.Getter;

import static org.lwjgl.opengl.GL33C.*;

public class GLRawBuffer extends GLBuffer {
	@Getter
	private int texId;

	@Getter
	private IntBuffer pixelBuffer;

	private ByteBuffer mappedBuffer;
	private boolean mapped;

	public GLRawBuffer(String name, int usage) {
		super(name, GL_TEXTURE_BUFFER, usage);
	}

	@Override
	public void initialize(long initialCapacity) {
		super.initialize(initialCapacity);

		// Create texture
		texId = glGenTextures();
		glBindTexture(GL_TEXTURE_BUFFER, texId);

		// RGB32 signed integer texture buffer
		glTexBuffer(GL_TEXTURE_BUFFER, GL_RGB32I, id);

		glBindTexture(GL_TEXTURE_BUFFER, 0);
	}

	@Override
	public void destroy() {
		if (mapped) {
			glBindBuffer(target, id);
			glUnmapBuffer(target);
			glBindBuffer(target, 0);
			mapped = false;
		}

		if (texId != 0) {
			glDeleteTextures(texId);
			texId = 0;
		}

		mappedBuffer = null;
		pixelBuffer = null;

		super.destroy();
	}

	public void map() {
		map(size);
	}

	public void map(long byteSize) {
		assert !mapped;

		ensureCapacity(byteSize);
		glBindBuffer(target, id);

		ByteBuffer buf;
		if (usage != GL_STATIC_DRAW) {
			buf = glMapBufferRange(
				target,
				0,
				byteSize,
				GL_MAP_WRITE_BIT
				| GL_MAP_FLUSH_EXPLICIT_BIT
				| GL_MAP_INVALIDATE_BUFFER_BIT,
				mappedBuffer
			);
		} else {
			buf = glMapBuffer(target, GL_WRITE_ONLY, mappedBuffer);
		}

		if (buf == null)
			throw new RuntimeException("Unable to map TBO buffer " + id + " size " + byteSize);

		if (buf != mappedBuffer) {
			mappedBuffer = buf;
			pixelBuffer = mappedBuffer.asIntBuffer();
		}

		pixelBuffer.position(0);
		glBindBuffer(target, 0);
		mapped = true;
	}

	public void unmap() {
		assert mapped;

		int written = pixelBuffer.position();
		glBindBuffer(target, id);

		if (usage != GL_STATIC_DRAW) {
			glFlushMappedBufferRange(
				target,
				0,
				(long) written * Integer.BYTES
			);
		}

		glUnmapBuffer(target);
		glBindBuffer(target, 0);

		mapped = false;
	}
}
