package rs117.hd.renderer.zone;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

public class RawTBO
{
	final int size; // bytes
	int glUsage;

	int bufId;
	int texId;

	IntBuffer tb;
	int len;
	boolean mapped;

	private ByteBuffer mappedBuffer;

	public RawTBO(int size)
	{
		this.size = size;
	}

	public void initialize(int glUsage)
	{
		this.glUsage = glUsage;

		// Create buffer
		bufId = glGenBuffers();
		glBindBuffer(GL_TEXTURE_BUFFER, bufId);
		glBufferData(GL_TEXTURE_BUFFER, size, glUsage);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);

		// Create texture
		texId = glGenTextures();
		glBindTexture(GL_TEXTURE_BUFFER, texId);

		// RGB32 signed integer texture buffer
		glTexBuffer(GL_TEXTURE_BUFFER, GL_RGB32I, bufId);

		glBindTexture(GL_TEXTURE_BUFFER, 0);
	}

	void destroy()
	{
		if (mapped)
		{
			glBindBuffer(GL_TEXTURE_BUFFER, bufId);
			glUnmapBuffer(GL_TEXTURE_BUFFER);
			glBindBuffer(GL_TEXTURE_BUFFER, 0);
			mapped = false;
		}

		if (texId != 0)
		{
			glDeleteTextures(texId);
			texId = 0;
		}

		if (bufId != 0)
		{
			glDeleteBuffers(bufId);
			bufId = 0;
		}

		mappedBuffer = null;
		tb = null;
	}

	public void map()
	{
		assert !mapped;

		glBindBuffer(GL_TEXTURE_BUFFER, bufId);

		ByteBuffer buf;
		if (glUsage != GL_STATIC_DRAW)
		{
			buf = glMapBufferRange(
				GL_TEXTURE_BUFFER,
				0,
				size,
				GL_MAP_WRITE_BIT
				| GL_MAP_FLUSH_EXPLICIT_BIT
				| GL_MAP_INVALIDATE_BUFFER_BIT,
				mappedBuffer
			);
		}
		else
		{
			buf = glMapBuffer(GL_TEXTURE_BUFFER, GL_WRITE_ONLY, mappedBuffer);
		}

		if (buf == null)
			throw new RuntimeException("Unable to map TBO buffer " + bufId + " size " + size);

		if (buf != mappedBuffer)
		{
			mappedBuffer = buf;
			tb = mappedBuffer.asIntBuffer();
		}

		tb.position(0);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);
		mapped = true;
	}

	void unmap()
	{
		assert mapped;

		len = tb.position();

		glBindBuffer(GL_TEXTURE_BUFFER, bufId);

		if (glUsage != GL_STATIC_DRAW)
		{
			glFlushMappedBufferRange(
				GL_TEXTURE_BUFFER,
				0,
				(long) len * Integer.BYTES
			);
		}

		glUnmapBuffer(GL_TEXTURE_BUFFER);
		glBindBuffer(GL_TEXTURE_BUFFER, 0);

		mapped = false;
	}
}
