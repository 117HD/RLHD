package rs117.hd.opengl;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import lombok.Value;
import org.lwjgl.system.MemoryStack;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;

public class GLPrimitives {

	@Value
	public static class Mesh {
		GLBuffer vbo;
		GLBuffer ebo;
		int indexCount;

		public void destroy() {
			vbo.destroy();
			ebo.destroy();
		}
	}

	public static Mesh buildCube(MemoryStack stack) {
		FloatBuffer vertices = stack.mallocFloat(24).put(new float[]{
			-1,-1,-1,  1,-1,-1,  1, 1,-1, -1, 1,-1,
			-1,-1, 1,  1,-1, 1,  1, 1, 1, -1, 1, 1
		}).flip();

		IntBuffer indices = stack.mallocInt(36).put(new int[]{
			0, 1, 2,  0, 2, 3,
			4, 6, 5,  4, 7, 6,
			0, 3, 7,  0, 7, 4,
			1, 5, 6,  1, 6, 2,
			0, 4, 5,  0, 5, 1,
			3, 2, 6,  3, 6, 7
		}).flip();

		return new Mesh(
			new GLBuffer("VBO::Cube", GL_ARRAY_BUFFER, GL_STATIC_DRAW).initialize(vertices),
			new GLBuffer.EBO("EBO::Cube", GL_STATIC_DRAW).initialize(indices),
			36
		);
	}

	public static Mesh buildSphere(MemoryStack stack, int stacks, int slices) {
		FloatBuffer vertices = stack.mallocFloat((stacks + 1) * (slices + 1) * 3);
		for (int s = 0; s <= stacks; s++) {
			float phi = (float) (Math.PI * s / stacks);
			for (int sl = 0; sl <= slices; sl++) {
				float theta = (float) (2 * Math.PI * sl / slices);
				vertices.put((float) (Math.sin(phi) * Math.cos(theta)));
				vertices.put((float)  Math.cos(phi));
				vertices.put((float) (Math.sin(phi) * Math.sin(theta)));
			}
		}
		vertices.flip();

		int indexCount = stacks * slices * 6;
		IntBuffer indices = stack.mallocInt(indexCount);
		for (int s = 0; s < stacks; s++) {
			for (int sl = 0; sl < slices; sl++) {
				int cur  = s * (slices + 1) + sl;
				int next = cur + (slices + 1);
				indices.put(cur    ).put(next    ).put(cur  + 1);
				indices.put(cur + 1).put(next    ).put(next + 1);
			}
		}
		indices.flip();

		return new Mesh(
			new GLBuffer("VBO::Sphere", GL_ARRAY_BUFFER, GL_STATIC_DRAW).initialize(vertices),
			new GLBuffer.EBO("EBO::Sphere", GL_STATIC_DRAW).initialize(indices),
			indexCount
		);
	}

	public static Mesh buildLine(MemoryStack stack) {
		FloatBuffer vertices = stack.mallocFloat(24).put(new float[]{
			-1,-1, 0,  1,-1, 0,  1, 1, 0, -1, 1, 0,
			0,-1,-1,  0,-1, 1,  0, 1, 1,  0, 1,-1
		}).flip();

		IntBuffer indices = stack.mallocInt(12).put(new int[]{
			0, 1, 2,  0, 2, 3,
			4, 5, 6,  4, 6, 7
		}).flip();

		return new Mesh(
			new GLBuffer("VBO::Line", GL_ARRAY_BUFFER, GL_STATIC_DRAW).initialize(vertices),
			new GLBuffer.EBO("EBO::Line", GL_STATIC_DRAW).initialize(indices),
			12
		);
	}

	public static Mesh buildQuad(MemoryStack stack) {
		FloatBuffer vertices = stack.mallocFloat(12).put(new float[]{
			0, 0, 0,
			1, 0, 0,
			1, 1, 0,
			0, 1, 0
		}).flip();

		IntBuffer indices = stack.mallocInt(6).put(new int[]{
			0, 1, 2,
			0, 2, 3
		}).flip();

		return new Mesh(
			new GLBuffer("VBO::Quad", GL_ARRAY_BUFFER, GL_STATIC_DRAW).initialize(vertices),
			new GLBuffer.EBO("EBO::Quad", GL_STATIC_DRAW).initialize(indices),
			6
		);
	}
}