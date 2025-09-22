package rs117.hd.utils.buffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glUnmapBuffer;
import static org.lwjgl.opengl.GL30C.GL_MAP_INVALIDATE_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.GL_MAP_UNSYNCHRONIZED_BIT;
import static org.lwjgl.opengl.GL30C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL30C.glMapBufferRange;

public class GLVBO extends GLBuffer {
	private ByteBuffer buffer;
	public IntBuffer vb;
	public int len;
	public boolean mapped;

	public GLVBO(String name)
	{
		super(name, GL_ARRAY_BUFFER, GL_DYNAMIC_DRAW);
	}

	public void destroy()
	{
		if (mapped)
		{
			glBindBuffer(GL_ARRAY_BUFFER, id);
			glUnmapBuffer(GL_ARRAY_BUFFER);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			mapped = false;
		}
		super.destroy();
	}

	public void map()
	{
		assert !mapped;
		glBindBuffer(GL_ARRAY_BUFFER, id);
		buffer = glMapBufferRange(GL_ARRAY_BUFFER, 0, size, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT | GL_MAP_UNSYNCHRONIZED_BIT, buffer);
		if (buffer == null)
		{
			throw new RuntimeException("unable to map GL buffer " + id + " size " + size);
		}
		this.vb = buffer.asIntBuffer();
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = true;
	}

	public void unmap()
	{
		assert mapped;
		len = vb.position();
		vb = null;

		glBindBuffer(GL_ARRAY_BUFFER, id);
		glUnmapBuffer(GL_ARRAY_BUFFER);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		mapped = false;
	}
}
