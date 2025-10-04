package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.opengl.texture.GLTexture;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class AsyncUICopy implements Runnable {
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer timer;

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Semaphore completionSemaphore = new Semaphore(0);

	private IntBuffer mappedBuffer;
	private int[] pixels;
	private GLTexture uiTex;
	private int width;
	private int height;

	@Override
	public void run() {
		long time = System.nanoTime();
		mappedBuffer.put(pixels, 0, width * height);
		time = System.nanoTime() - time;
		completionSemaphore.release();
		timer.add(Timer.COPY_UI, time);
	}

	public void prepare(GLTexture uiTex) {
		// Ensure there isn't already another UI copy in progress
		if (mappedBuffer != null)
			return;

		timer.begin(Timer.MAP_UI_BUFFER);
		ByteBuffer buffer = uiTex.map(GL_WRITE_ONLY);
		timer.end(Timer.MAP_UI_BUFFER);
		if (buffer == null) {
			log.error("Unable to map interface PBO. Skipping UI...");
			return;
		}

		this.uiTex = uiTex;
		this.mappedBuffer = buffer.asIntBuffer();

		var provider = client.getBufferProvider();
		this.pixels = provider.getPixels();
		this.width = provider.getWidth();
		this.height = provider.getHeight();

		executor.execute(this);
	}

	public boolean complete() {
		if (mappedBuffer == null)
			return false;

		try {
			// It shouldn't take this long even in the worst case
			boolean acquired = completionSemaphore.tryAcquire(1, 100, TimeUnit.MILLISECONDS);
			if (!acquired)
				return false;
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}

		if (width > uiTex.getWidth() || height > uiTex.getHeight()) {
			log.error("UI texture resolution mismatch ({}x{} > {}x{}). Skipping UI...", width, height, uiTex.getWidth(), uiTex.getHeight());
			return false;
		}

		timer.begin(Timer.UPLOAD_UI);
		uiTex.unmap(0, 0, width, height);
		timer.end(Timer.UPLOAD_UI);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

		mappedBuffer = null;
		pixels = null;
		return true;
	}
}
