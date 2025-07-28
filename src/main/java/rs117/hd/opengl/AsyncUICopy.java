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
	private int interfacePbo;
	private int interfaceTexture;
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

	public void prepare(int interfacePbo, int interfaceTex) {
		// Ensure there isn't already another UI copy in progress
		if (mappedBuffer != null)
			return;

		timer.begin(Timer.MAP_UI_BUFFER);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		ByteBuffer buffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		timer.end(Timer.MAP_UI_BUFFER);
		if (buffer == null) {
			log.error("Unable to map interface PBO. Skipping UI...");
			return;
		}

		this.interfacePbo = interfacePbo;
		this.interfaceTexture = interfaceTex;
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

		var uiResolution = plugin.getUiResolution();
		if (uiResolution == null || width > uiResolution[0] || height > uiResolution[1]) {
			log.error("UI texture resolution mismatch ({}x{} > {}). Skipping UI...", width, height, uiResolution);
			return false;
		}

		timer.begin(Timer.UPLOAD_UI);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		glActiveTexture(HdPlugin.TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		timer.end(Timer.UPLOAD_UI);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

		mappedBuffer = null;
		pixels = null;
		return true;
	}
}
