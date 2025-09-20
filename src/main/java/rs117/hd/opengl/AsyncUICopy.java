package rs117.hd.opengl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import javax.inject.Inject;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.Job;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
public class AsyncUICopy extends Job {
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private FrameTimer timer;

	private ByteBuffer mappedBuffer;
	private IntBuffer mappedIntBuffer;
	private int[] pixels;
	@Setter
	private int interfacePbo;
	@Setter
	private int interfaceTexture;
	@Setter
	private boolean resize;
	private int width;
	private int height;

	@Override
	protected void onPrepare() {
		var provider = client.getBufferProvider();
		pixels = provider.getPixels();
		width = provider.getWidth();
		height = provider.getHeight();

		timer.begin(Timer.MAP_UI_BUFFER);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		mappedBuffer = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY, width * height * (long) Integer.BYTES, mappedBuffer);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		timer.end(Timer.MAP_UI_BUFFER);

		if (mappedBuffer == null) {
			log.error("Unable to map interface PBO. Skipping UI...");
			return;
		}

		mappedBuffer.clear();
		mappedIntBuffer = mappedBuffer.asIntBuffer();
	}

	@Override
	protected void doWork() {
		if (pixels != null && mappedIntBuffer != null) {
			long time = System.nanoTime();
			mappedIntBuffer.put(pixels, 0, width * height);
			time = System.nanoTime() - time;
			timer.add(Timer.COPY_UI, time);
		}
	}

	@Override
	protected void onComplete() {
		var uiResolution = plugin.getUiResolution();
		if (uiResolution == null || width > uiResolution[0] || height > uiResolution[1]) {
			log.error("UI texture resolution mismatch ({}x{} > {}). Skipping UI...", width, height, uiResolution);
			return;
		}

		if (!resize) timer.begin(Timer.UPLOAD_UI);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		glActiveTexture(HdPlugin.TEXTURE_UNIT_UI);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		if (!resize) timer.end(Timer.UPLOAD_UI);

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
	}
}
