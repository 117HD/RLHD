package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.runelite.api.*;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;

import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL21C.*;

public class AsyncInterfaceCopy implements Runnable {

	private FrameTimer timer;
	private ExecutorService executor;

	private final Semaphore completionSemaphore = new Semaphore(0);

	private IntBuffer mappedBuffer;
	private int[] pixels;
	private int interfacePho;
	private int interfaceTexture;
	private int width;
	private int height;

	public AsyncInterfaceCopy(FrameTimer timer) {
		this.timer = timer;
		executor = Executors.newSingleThreadExecutor();
	}

	@Override
	public void run() {
		mappedBuffer.put(pixels, 0, width * height);
		completionSemaphore.release();
	}

	public void prepare(BufferProvider provider, int interfacePho, int interfaceTex) {
		if (mappedBuffer != null) {
			return;
		}

		timer.begin(Timer.COPY_UI);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePho);
		ByteBuffer mappedBuffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

		if (mappedBuffer == null) {
			timer.end(Timer.COPY_UI);
			return;
		}

		this.interfacePho = interfacePho;
		this.interfaceTexture = interfaceTex;
		this.mappedBuffer = mappedBuffer.asIntBuffer();
		this.pixels = provider.getPixels();
		this.width = provider.getWidth();
		this.height = provider.getHeight();

		executor.execute(this);
		timer.end(Timer.COPY_UI);
	}

	public boolean complete() {
		// Check if there are any workers doing anything
		if (mappedBuffer == null) {
			return false;
		}

		timer.begin(Timer.COPY_UI);
		try {
			// Timeout after couple ms, shouldn't take more than a millisecond in the worst case
			completionSemaphore.tryAcquire(1, 12, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		timer.end(Timer.COPY_UI);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePho);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);

		mappedBuffer = null;
		pixels = null;
		return true;
	}
}