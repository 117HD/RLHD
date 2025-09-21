package rs117.hd.utils.buffer;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_INT;
import static org.lwjgl.opengl.GL11C.GL_SHORT;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL20C.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20C.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;
import static org.lwjgl.opengl.GL30C.glVertexAttribI3i;
import static org.lwjgl.opengl.GL30C.glVertexAttribIPointer;

class GLVAO
{
	// Temporary vertex format
	// index 0: vec3(x, y, z)
	// index 1: Short.MIN_VALUE (non-array)
	// index 2: int abhsl
	// index 3: short vec4(id, x, y, z)
	static final int VERT_SIZE = 24;

	final GLVBO vbo;
	int vao;

	GLVAO(int size)
	{
		vbo = new GLVBO(size);
	}

	void init()
	{
		vao = glGenVertexArrays();
		glBindVertexArray(vao);

		vbo.init();
		glBindBuffer(GL_ARRAY_BUFFER, vbo.bufId);

		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, VERT_SIZE, 0);

		glVertexAttribI3i(1, Short.MIN_VALUE, Short.MIN_VALUE, Short.MIN_VALUE);

		glEnableVertexAttribArray(2);
		glVertexAttribIPointer(2, 1, GL_INT, VERT_SIZE, 12);

		glEnableVertexAttribArray(3);
		glVertexAttribIPointer(3, 4, GL_SHORT, VERT_SIZE, 16);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	void destroy()
	{
		vbo.destroy();
		glDeleteVertexArrays(vao);
		vao = 0;
	}
}

@Slf4j
class GLVAOList
{
	// this needs to be larger than the largest single model
	//	private static final int VAO_SIZE = 16 * 1024 * 1024;
	private static final int VAO_SIZE = 1024 * 1024;

	private int curIdx;
	private final List<GLVAO> vaos = new ArrayList<>();

	GLVAO get(int size)
	{
		assert size <= VAO_SIZE;

		while (curIdx < vaos.size())
		{
			GLVAO vao = vaos.get(curIdx);
			if (!vao.vbo.mapped)
			{
				vao.vbo.map();
			}

			int rem = vao.vbo.vb.remaining() * Integer.BYTES;
			if (size <= rem)
			{
				return vao;
			}

			curIdx++;
		}

		GLVAO vao = new GLVAO(VAO_SIZE);
		vao.init();
		vao.vbo.map();
		vaos.add(vao);
		log.trace("Allocated VAO {}", vao.vao);
		return vao;
	}

	List<GLVAO> unmap()
	{
		int sz = 0;
		for (GLVAO vao : vaos)
		{
			if (vao.vbo.mapped)
			{
				++sz;
				vao.vbo.unmap();
			}
		}
		curIdx = 0;
		return vaos.subList(0, sz);
	}

	void free()
	{
		for (GLVAO vao : vaos)
		{
			vao.destroy();
		}
		vaos.clear();
		curIdx = 0;
	}
}
