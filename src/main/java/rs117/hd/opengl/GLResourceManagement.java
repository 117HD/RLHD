package rs117.hd.opengl;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.lwjgl.opengl.*;

import static java.util.Arrays.copyOf;
import static org.lwjgl.opengl.GL11C.glGetInteger;
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
	private static final BufferPool VAO_POOL = new BufferPool(-1, GL30C::glGenVertexArrays, GL30C::glDeleteVertexArrays);
	private static BufferPool[] BUFFER_POOLS = new BufferPool[4];
	private static int BUFFER_POOL_COUNT = 0;

	private static BufferPool obtainBufferPool(int target) {
		for(int i = 0; i < BUFFER_POOL_COUNT; i++) {
			if(BUFFER_POOLS[i].target == target)
				return BUFFER_POOLS[i];
		}

		if(BUFFER_POOL_COUNT >= BUFFER_POOLS.length)
			BUFFER_POOLS = copyOf(BUFFER_POOLS, BUFFER_POOLS.length * 2);

		return BUFFER_POOLS[BUFFER_POOL_COUNT++] = new BufferPool(target, GL15C::glGenBuffers, GL15C::glDeleteBuffers);
	}

	public static int obtainBuffer(int target) {
		return obtainBufferPool(target).obtain();
	}

	public static void releaseBuffer(int target, int id) {
		obtainBufferPool(target).release(id);
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
