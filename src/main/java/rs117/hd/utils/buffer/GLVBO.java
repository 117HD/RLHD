package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL15C.glUnmapBuffer;
import static org.lwjgl.opengl.GL30C.GL_MAP_INVALIDATE_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.GL_MAP_UNSYNCHRONIZED_BIT;
import static org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30C.glMapBufferRange;

public class GLVBO {
	final int size;
	int bufId; // TODO: We should make this inherit from GLBuffer
	private ByteBuffer buffer;
	IntBuffer vb;
	int len;
	boolean mapped;

	GLVBO(int size)
	{
		this.size = size;
	}

	void init()
	{
		bufId = glGenBuffers();

		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		glBufferData(GL_ARRAY_BUFFER, size, GL_DYNAMIC_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	void destroy()
	{
		if (mapped)
		{
			glBindBuffer(GL_ARRAY_BUFFER, bufId);
			glUnmapBuffer(GL_ARRAY_BUFFER);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			mapped = false;
		}
		glDeleteBuffers(bufId);
		bufId = 0;
	}

	void map()
	{
		assert !mapped;
		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		buffer = glMapBufferRange(GL_ARRAY_BUFFER, 0, size, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT | GL_MAP_UNSYNCHRONIZED_BIT, buffer);
		if (buffer == null)
		{
			throw new RuntimeException("unable to map GL buffer " + bufId + " size " + size);
		}
		this.vb = buffer.asIntBuffer();
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = true;
	}

	void unmap()
	{
		assert mapped;
		len = vb.position();
		vb = null;

		glBindBuffer(GL_ARRAY_BUFFER, bufId);
		glUnmapBuffer(GL_ARRAY_BUFFER);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = false;
	}
}
