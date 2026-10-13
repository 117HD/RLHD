package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import javax.inject.Inject;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.jobs.Job;

public final class EboAlphaWriterJob extends Job {

	@Inject
	private FrameTimer frameTimer;

	public final WorldViewContext context;

	// The region reserved in the shared EBO this frame, copied when the job runs, or null when nothing was reserved.
	private GLMappedBufferIntWriter.ReservedView eboAlphaView;
	private int pendingCount;

	public EboAlphaWriterJob(WorldViewContext context) {
		super(true);
		this.context = context;
	}

	/**
	 * Reserves space in the shared EBO for this world view's packed alpha-index blocks, scheduling the copy of
	 * {@code alphaCount} ints from {@link WorldViewContext#eboAlphaIndices} when the job runs.
	 *
	 * @return the absolute EBO offset the reserved region starts at, or -1 if nothing was reserved.
	 */
	public int reserve(int alphaCount) {
		if (alphaCount <= 0)
			return -1;

		eboAlphaView = ZoneRenderer.eboAlphaWriter.reserve(alphaCount);
		pendingCount = alphaCount;
		return eboAlphaView.getBufferOffsetInts();
	}

	@Override
	protected void onRun() {
		long start = System.nanoTime();

		final GLMappedBufferIntWriter.ReservedView view = eboAlphaView;
		eboAlphaView = null;
		if (view != null) {
			final IntBuffer eboAlphaBuffer = view.getBuffer();
			if (eboAlphaBuffer != null)
				eboAlphaBuffer.put(context.eboAlphaIndices, 0, pendingCount);
		}
		pendingCount = 0;

		frameTimer.add(Timer.STATIC_ALPHA_UPLOAD, System.nanoTime() - start);
	}
}
