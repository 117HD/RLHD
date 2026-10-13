package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.Arrays;
import javax.inject.Inject;
import lombok.Getter;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

/**
 * Sorts the alpha faces of every distinct {@link StaticAlphaModel} referenced by a {@link WorldViewContext}, assigning
 * each model a contiguous block of sorted indices in the shared index buffer. Because a {@link StaticAlphaModel} is
 * interned per world view, a single job handles every zone in the world view, and each distinct model's faces are
 * sorted at most once per camera orientation.
 */
public final class StaticAlphaSortingJob extends Job {

	@Inject
	private FrameTimer frameTimer;

	public final WorldViewContext context;

	private GLMappedBufferIntWriter.ReservedView eboAlphaView;

	private Camera camera;

	private StaticAlphaModel[] blockModels = new StaticAlphaModel[16];
	private int blockCount = 0;
	@Getter
	private int assignedIndexCount = 0;

	public StaticAlphaSortingJob(WorldViewContext context) {
		super(true);
		this.context = context;
	}

	public void reset() {
		for (int block = 0; block < blockCount; block++)
			blockModels[block].sortedIndiciesOffset = -1;
		Arrays.fill(blockModels, 0, blockCount, null);
		blockCount = 0;
		assignedIndexCount = 0;
	}

	/**
	 * Assigns the given model a contiguous block of sorted indices, if it does not already have one this frame.
	 */
	public void addStaticAlphaModel(StaticAlphaModel model) {
		if (model == null || model.sortedIndiciesOffset >= 0)
			return;

		final int block = blockCount++;
		if (blockModels.length <= block)
			blockModels = Arrays.copyOf(blockModels, ceilPow2(block + 1));
		blockModels[block] = model;

		model.sortedIndiciesOffset = assignedIndexCount;
		assignedIndexCount += model.getFaceCount() * 3;
	}

	public int queue(Camera camera) {
		if (blockCount == 0)
			return 0;

		this.camera = camera;
		this.eboAlphaView = ZoneRenderer.eboAlphaWriter.reserve(assignedIndexCount);
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

		// Face order depends only on camera orientation, so this identifies a StaticAlphaModel's cached sort
		final int sortKey = yaw << 16 | pitch;
		final int[] sortedAlphaIndices = PooledArrayType.INT.borrow(assignedIndexCount);
		try (final FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			int sortedOffset = 0;
			for (int block = 0; block < blockCount; block++) {
				final StaticAlphaModel model = blockModels[block];

				synchronized (model) {
					if (model.sortKey != sortKey || model.sortedFaces == null) {
						sorter.sortStaticModelFaces(model, yawCos, yawSin, pitchCos, pitchSin);
						model.sortKey = sortKey;
					}
				}

				final int[] sortedFaces = model.sortedFaces;
				final int sortedFaceCount = model.sortedFaceCount;
				if (sortedFaces == null || sortedFaceCount <= 0)
					return;

				for (int i = 0; i < sortedFaceCount; i++) {
					final int faceStart = sortedFaces[i] * 3;
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
