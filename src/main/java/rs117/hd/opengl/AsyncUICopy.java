package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.jobs.JobSystem;
import rs117.hd.utils.jobs.JobWork;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public final class AsyncUICopy extends JobWork {
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer timer;

	@Inject
	private JobSystem jobSystem;

	private ByteBuffer mappedBuffer;
	private IntBuffer mappedIntBuffer;
	private int[] pixels;
	private int interfacePbo;
	private int interfaceTexture;
	private int width;
	private int height;
	private boolean inFlight = false;

	public void prepare(int interfacePbo, int interfaceTex) {
		// Ensure there isn't already another UI copy in progress
		if (mappedBuffer != null)
			return;

		var provider = client.getBufferProvider();
		this.pixels = provider.getPixels();
		this.width = provider.getWidth();
		this.height = provider.getHeight();
		this.interfacePbo = interfacePbo;
		this.interfaceTexture = interfaceTex;

		timer.begin(Timer.MAP_UI_BUFFER);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		ByteBuffer buffer = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, (long) width * height * Integer.BYTES, GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT, mappedBuffer);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		timer.end(Timer.MAP_UI_BUFFER);

		if (buffer == null) {
			log.error("Unable to map interface PBO. Skipping UI...");
			return;
		}

		if(buffer != mappedBuffer) {
			mappedBuffer = buffer;
			mappedIntBuffer = mappedBuffer.asIntBuffer();
		}

		setExecuteAsync(client.getGameState() == GameState.LOGGED_IN);
		queue();
		inFlight = true;
	}

	public boolean complete() {
		if (mappedBuffer == null || !inFlight)
			return false;

		long timestamp = System.nanoTime();
		waitForCompletion();
		inFlight = false;

		var uiResolution = plugin.getUiResolution();
		if (uiResolution == null || width > uiResolution[0] || height > uiResolution[1]) {
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			log.error("UI texture resolution mismatch ({}x{} > {}). Skipping UI...", width, height, uiResolution);
			return false;
		}

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);

		glActiveTexture(HdPlugin.TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		timer.addTimestamp(Timer.COPY_UI, timestamp);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

		mappedBuffer = null;
		pixels = null;
		return true;
	}

	@Override
	protected void onRun() {
		mappedIntBuffer.put(pixels, 0, width * height);
	}

	@Override
	protected void onCancel() {}

	@Override
	protected void onReleased() {}
}
