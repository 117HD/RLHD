package rs117.hd.renderer.zone;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL32C.GL_ALREADY_SIGNALED;
import static org.lwjgl.opengl.GL32C.GL_CONDITION_SATISFIED;
import static org.lwjgl.opengl.GL32C.GL_SYNC_FLUSH_COMMANDS_BIT;
import static org.lwjgl.opengl.GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE;
import static org.lwjgl.opengl.GL32C.GL_WAIT_FAILED;
import static org.lwjgl.opengl.GL32C.glClientWaitSync;
import static org.lwjgl.opengl.GL32C.glDeleteSync;
import static org.lwjgl.opengl.GL32C.glFenceSync;

@Slf4j
public class FrameContext {
	private static final long FENCE_SYNC_TIMEOUT_NS = SECONDS.toNanos(1);

	private static long
		lastFenceCreationWarningMs,
		lastFenceWaitWarningMs,
		lastFenceTimeoutWarningMs;

	private static int
		fenceCreationFailureCount,
		fenceWaitFailureCount,
		fenceTimeoutCount;

	public static final int VAO_OPAQUE = 0;
	public static final int VAO_ALPHA = 1;
	public static final int VAO_PLAYER = 2;
	public static final int VAO_SHADOW = 3;
	public static final int VAO_PRESCENE = 4;
	public static final int VAO_COUNT = 5;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer frameTimer;

	final DynamicModelVAO[] dynamicModelVaos = new DynamicModelVAO[VAO_COUNT];

	private long fence;

	void initBuffers() {
		for (int i = 0; i < VAO_COUNT; i++) {
			dynamicModelVaos[i] = new DynamicModelVAO("DynamicModelVAO_" + i, i == VAO_OPAQUE || i == VAO_PLAYER || i == VAO_SHADOW);
			if (dynamicModelVaos[i].getVao() == 0)
				dynamicModelVaos[i].initialize();
		}
	}

	void destroy() {
		for (int i = 0; i < VAO_COUNT; i++) {
			if (dynamicModelVaos[i] != null)
				dynamicModelVaos[i].destroy();
			dynamicModelVaos[i] = null;
		}

		if(fence != 0)
			glDeleteSync(fence);
		fence = 0;
	}

	void map() {
		for (int i = 0; i < VAO_COUNT; i++)
			dynamicModelVaos[i].map();
	}

	int obtainDrawIndex(int type) {
		return dynamicModelVaos[type].obtainDrawIndex();
	}

	void unmap() {
		for (int i = 0; i < VAO_COUNT; i++)
			dynamicModelVaos[i].unmap();
	}

	void fence() {
		if(!plugin.configFrameSynchronization)
			return;

		assert fence == 0 : "Frame fence already set, this should never happen";
		fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		if (fence == 0) {
			fenceCreationFailureCount++;
			long now = System.currentTimeMillis();
			if (fenceCreationFailureCount == 1 || now - lastFenceCreationWarningMs >= 1000) {
				log.warn("Unable to create frame fence, count {}. Falling back to glFinish...", fenceCreationFailureCount);
				lastFenceCreationWarningMs = now;
			}
			glFinish();
		}
	}

	void sync() {
		// Drain existing fences even if synchronization has been disabled
		if(fence == 0)
			return;

		try (var ignored = frameTimer.begin(Timer.FRAME_FENCE_WAIT)) {
			final long deadline = System.nanoTime() + FENCE_SYNC_TIMEOUT_NS;
			int flags = GL_SYNC_FLUSH_COMMANDS_BIT;
			boolean waitFailed = false;
			long remaining;

			while ((remaining = deadline - System.nanoTime()) > 0) {
				final int result = glClientWaitSync(fence, flags, remaining);
				flags = 0; // only flush commands on the first sync

				if (result == GL_ALREADY_SIGNALED || result == GL_CONDITION_SATISFIED)
					return;

				if (result == GL_WAIT_FAILED) {
					waitFailed = true;
					break;
				}
			}

			long now = System.currentTimeMillis();
			if (waitFailed) {
				fenceWaitFailureCount++;
				if (fenceWaitFailureCount == 1 || now - lastFenceWaitWarningMs >= 1000) {
					log.warn("glClientWaitSync failed, count {}. Falling back to glFinish...", fenceWaitFailureCount);
					lastFenceWaitWarningMs = now;
				}
			} else {
				fenceTimeoutCount++;
				if (fenceTimeoutCount == 1 || now - lastFenceTimeoutWarningMs >= 1000) {
					log.warn(
						"Timed out waiting {}ms for frame fence, count {}. Falling back to glFinish...",
						NANOSECONDS.toMillis(FENCE_SYNC_TIMEOUT_NS), fenceTimeoutCount
					);
					lastFenceTimeoutWarningMs = now;
				}
			}

			glFinish();
		} finally {
			glDeleteSync(fence);
			fence = 0;
		}
	}
}
