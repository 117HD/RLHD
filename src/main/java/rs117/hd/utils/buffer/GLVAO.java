package rs117.hd.utils.buffer;

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

public class GLVAO
{
	// Temporary vertex format
	// index 0: vec3(x, y, z)
	// index 1: Short.MIN_VALUE (non-array)
	// index 2: int abhsl
	// index 3: short vec4(id, x, y, z)
	static final int VERT_SIZE = 24;

	final GLVBO vbo;
	int vao;

	GLVAO(String name)
	{
		vbo = new GLVBO(name);
	}

	public void initialize(int size)
	{
		vao = glGenVertexArrays();
		glBindVertexArray(vao);

		vbo.initialize(size);
		glBindBuffer(GL_ARRAY_BUFFER, vbo.id);

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