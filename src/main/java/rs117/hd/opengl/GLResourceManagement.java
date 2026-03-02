package rs117.hd.opengl;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.lwjgl.opengl.*;

import static java.util.Arrays.copyOf;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL20.GL_MAX_VERTEX_ATTRIBS;
import static org.lwjgl.opengl.GL20.glDisableVertexAttribArray;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public class GLResourceManagement {
	@FunctionalInterface
	interface Deleter {
		void delete(int id);
	}

	@RequiredArgsConstructor
	static final class BufferPool {
		final int target;
		final int usage;
		final Supplier<Integer> gen;
		final Deleter deleter;
		int[] unused = new int[16];
		int count;


		int obtain() {
			return count > 0 ? unused[--count] : gen.get();
		}

		void release(int id) {
			if(count >= unused.length)
				unused = copyOf(unused, unused.length * 2);
			unused[count++] = id;
		}

		void destroy() {
			for(int i = 0; i < count; i++)
				deleter.delete(unused[i]);
			count = 0;
		}
	}

	private static int MAX_VERTEX_ATTRIBS_VALUE = -1;
	private static final BufferPool VAO_POOL = new BufferPool(-1, -1, GL30::glGenVertexArrays, GL30::glDeleteVertexArrays);
	private static BufferPool[] BUFFER_POOLS = new BufferPool[4];
	private static int BUFFER_POOL_COUNT = 0;

	private static BufferPool obtainBufferPool(int target, int usage) {
		for(int i = 0; i < BUFFER_POOL_COUNT; i++) {
			if(BUFFER_POOLS[i].target == target && BUFFER_POOLS[i].usage == usage)
				return BUFFER_POOLS[i];
		}

		if(BUFFER_POOL_COUNT >= BUFFER_POOLS.length)
			BUFFER_POOLS = copyOf(BUFFER_POOLS, BUFFER_POOLS.length * 2);

		return BUFFER_POOLS[BUFFER_POOL_COUNT++] = new BufferPool(target, usage, GL15::glGenBuffers, GL15::glDeleteBuffers);
	}

	public static int obtainBuffer(int target, int usage) {
		return obtainBufferPool(target, usage).obtain();
	}

	public static void releaseBuffer(int target, int usage, int id, boolean isStorage) {
		if(isStorage) {
			glDeleteBuffers(id);
		} else {
			obtainBufferPool(target, usage).release(id);
		}
	}

	public static int obtainVAO() {
		return VAO_POOL.obtain();
	}

	public static void releaseVAO(int vao) {
		if(MAX_VERTEX_ATTRIBS_VALUE == -1)
			MAX_VERTEX_ATTRIBS_VALUE = glGetInteger(GL_MAX_VERTEX_ATTRIBS);

		glBindVertexArray(vao);
		for(int i = 0; i < MAX_VERTEX_ATTRIBS_VALUE; i++)
			glDisableVertexAttribArray(i);
		glBindVertexArray(0);

		VAO_POOL.release(vao);
	}

	public static void destroyUnusedBuffers() {
		for (int i = 0; i < BUFFER_POOL_COUNT; i++)
			BUFFER_POOLS[i].destroy();
	}
}
