package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.Arrays;
import javax.inject.Inject;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

public final class StaticAlphaSortingJob extends Job {


	public final WorldViewContext context;

	@Inject
	private FrameTimer frameTimer;

	private StaticAlphaModel[] pendingModels = new StaticAlphaModel[16];
	private int pending = 0;
	private int indiciesCount = 0;

	private Camera camera;
	private GLMappedBufferIntWriter.ReservedView eboAlphaView;

	public StaticAlphaSortingJob(WorldViewContext context) {
		super(true);
		this.context = context;
	}

	public void reset() {
		for (int block = 0; block < pending; block++)
			pendingModels[block].sortedIndiciesOffset = -1;
		Arrays.fill(pendingModels, 0, pending, null);
		pending = 0;
		indiciesCount = 0;
	}

	public void addStaticAlphaModel(StaticAlphaModel model) {
		if (model == null || model.sortedIndiciesOffset >= 0)
			return;

		final int idx = pending++;
		if (pendingModels.length <= idx)
			pendingModels = Arrays.copyOf(pendingModels, ceilPow2(idx + 1));
		pendingModels[idx] = model;

		model.sortedIndiciesOffset = indiciesCount;
		indiciesCount += model.getFaceCount() * 3;
	}

	public int queue(Camera camera) {
		if (pending == 0)
			return 0;

		this.camera = camera;
		this.eboAlphaView = ZoneRenderer.eboAlphaWriter.reserve(indiciesCount);
		final int offsetInts = eboAlphaView.getBufferOffsetInts();

		queue();
		return offsetInts;
	}

	public void destroy() {
		waitForCompletion();
		reset();
	}

	@Override
	protected void onRun() {
		long start = System.nanoTime();
		final GLMappedBufferIntWriter.ReservedView view = eboAlphaView;
		eboAlphaView = null;

		final int yaw = camera.getFixedYaw();
		final int yawSin = SINE14[yaw];
		final int yawCos = COSINE14[yaw];

		final int pitch = camera.getFixedPitch();
		final int pitchSin = SINE14[pitch];
		final int pitchCos = COSINE14[pitch];

		final int sortKey = yaw << 16 | pitch;

		final int[] sortedAlphaIndices = PooledArrayType.INT.borrow(indiciesCount);
		try (final FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			int sortedOffset = 0;
			for (int i = 0; i < pending; i++) {
				final StaticAlphaModel model = pendingModels[i];

				if (model.sortKey != sortKey) {
					model.sortKey = sortKey;
					sorter.sortStaticModelFaces(model, yawCos, yawSin, pitchCos, pitchSin);
				}

				final int sortedFaceCount = model.sortedFaceCount;
				if (sortedFaceCount <= 0)
					return;

				final char[] sortedFaces = model.sortedFaces;
				for (int f = 0; f < sortedFaceCount; f++) {
					final int faceStart = sortedFaces[f] * 3;
					sortedAlphaIndices[sortedOffset++] = faceStart;
					sortedAlphaIndices[sortedOffset++] = faceStart + 1;
					sortedAlphaIndices[sortedOffset++] = faceStart + 2;
				}
			}

			if (view != null) {
				final IntBuffer eboAlphaBuffer = view.getBuffer();
				if (eboAlphaBuffer != null)
					eboAlphaBuffer.put(sortedAlphaIndices, 0, sortedOffset);
			}
		} finally {
			PooledArrayType.INT.release(sortedAlphaIndices);
			frameTimer.add(Timer.STATIC_ALPHA_SORT, System.nanoTime() - start);
		}
	}
}
