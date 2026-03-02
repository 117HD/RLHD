package rs117.hd.opengl;

import lombok.RequiredArgsConstructor;

import static java.util.Arrays.copyOf;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;

public class GLResourceManagement {
	@RequiredArgsConstructor
	static class BufferPool {
		final int target;
		int[] unused = new int[16];
		int count;

		int obtain() {
			if(count > 0)
				return unused[--count];
			else
				return glGenBuffers();
		}

		void release(int id) {
			if(count >= unused.length)
				unused = copyOf(unused, unused.length * 2);
			unused[count++] = id;
		}

		void destroy() {
			for(int i = 0; i < count; i++)
				glDeleteBuffers(unused[i]);
			count = 0;
		}
	}

	private static BufferPool[] BUFFER_POOLS = new BufferPool[4];
	private static int BUFFER_POOL_COUNT = 0;

	private static BufferPool obtainBufferPool(int target) {
		for(int i = 0; i < BUFFER_POOL_COUNT; i++) {
			if(BUFFER_POOLS[i].target == target)
				return BUFFER_POOLS[i];
		}

		if(BUFFER_POOL_COUNT >= BUFFER_POOLS.length)
			BUFFER_POOLS = copyOf(BUFFER_POOLS, BUFFER_POOLS.length * 2);

		return BUFFER_POOLS[BUFFER_POOL_COUNT++] = new BufferPool(target);
	}

	public static int obtainBuffer(int target) {
		return obtainBufferPool(target).obtain();
	}

	public static void releaseBuffer(int target, int id) {
		obtainBufferPool(target).release(id);
	}

	public static void destroyUnusedBuffers() {
		for (int i = 0; i < BUFFER_POOL_COUNT; i++)
			BUFFER_POOLS[i].destroy();
	}
}
