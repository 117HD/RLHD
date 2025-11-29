package rs117.hd.renderer.zone;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

class VBO {
	final int size;
	int glUsage;
	int bufId;
	IntBuffer vb;
	int len;
	boolean mapped;

	private ByteBuffer mappedBuffer;

	VBO(int size) {
		this.size = size;
	}

	void initialize(int glUsage) {
		this.glUsage = glUsage;
		bufId = glGenBuffers();
		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		glBufferData(GL_ARRAY_BUFFER, size, glUsage);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	void destroy() {
		if (mapped) {
			glBindBuffer(GL_ARRAY_BUFFER, bufId);
			glUnmapBuffer(GL_ARRAY_BUFFER);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			mapped = false;
		}
		glDeleteBuffers(bufId);
		bufId = 0;
	}

	void map() {
		assert !mapped;
		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		if (glUsage != GL_STATIC_DRAW) {
			mappedBuffer = glMapBufferRange(
				GL_ARRAY_BUFFER,
				0,
				size,
				// TODO: GL_MAP_UNSYNCHRONIZED_BIT breaks alpha on AMD GPUs
				GL_MAP_WRITE_BIT | GL_MAP_FLUSH_EXPLICIT_BIT | GL_MAP_INVALIDATE_BUFFER_BIT,
				mappedBuffer
			);
		} else {
			mappedBuffer = glMapBuffer(GL_ARRAY_BUFFER, GL_WRITE_ONLY, mappedBuffer);
		}
		if (mappedBuffer == null)
			throw new RuntimeException("unable to map GL buffer " + bufId + " size " + size);
		this.vb = mappedBuffer.asIntBuffer();
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = true;
	}

	void unmap() {
		assert mapped;
		len = vb.position();
		vb = null;

		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		if (glUsage != GL_STATIC_DRAW)
			glFlushMappedBufferRange(GL_ARRAY_BUFFER, 0, (long) len * Integer.BYTES);
		glUnmapBuffer(GL_ARRAY_BUFFER);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = false;
	}
}
