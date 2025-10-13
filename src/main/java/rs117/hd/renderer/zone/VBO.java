package rs117.hd.renderer.zone;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

class VBO {
	final int size;
	int bufId;
	private ByteBuffer buffer;
	IntBuffer vb;
	int len;
	boolean mapped;

	VBO(int size) {
		this.size = size;
	}

	void init() {
		bufId = glGenBuffers();

		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		glBufferData(GL_ARRAY_BUFFER, size, GL_DYNAMIC_DRAW);
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
		buffer = glMapBufferRange(
			GL_ARRAY_BUFFER,
			0,
			size,
			GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT | GL_MAP_UNSYNCHRONIZED_BIT,
			buffer
		);
		if (buffer == null) {
			throw new RuntimeException("unable to map GL buffer " + bufId + " size " + size);
		}
		this.vb = buffer.asIntBuffer();
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = true;
	}

	void unmap() {
		assert mapped;
		len = vb.position();
		vb = null;

		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		glUnmapBuffer(GL_ARRAY_BUFFER);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = false;
	}
}
