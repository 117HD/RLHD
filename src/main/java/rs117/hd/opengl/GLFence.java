package rs117.hd.opengl;

import static org.lwjgl.opengl.GL33C.*;

public class GLFence {
	public long handle;

	public void sync() {
		sync(GL_TIMEOUT_IGNORED);
	}

	public synchronized void sync(long nanoseconds) {
		if (handle == 0)
			return;

		glClientWaitSync(handle, GL_SYNC_FLUSH_COMMANDS_BIT, nanoseconds);
		glDeleteSync(handle);
		handle = 0;
	}
}
