package rs117.hd.renderer.zone;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;
import lombok.RequiredArgsConstructor;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.Zone.AlphaModel;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.HDUtils.ceilPow2;
import static rs117.hd.utils.MathUtils.*;

@RequiredArgsConstructor
public final class StaticAlphaSortingJob extends Job {
	private static final int DIST_THRESHOLD = 4096 * 4096;

	private FrameTimer frameTimer;

	private final FacePrioritySorter staticSorter;

	private AlphaModel[] models = new AlphaModel[16];
	private AtomicIntegerArray states = new AtomicIntegerArray(16);
	private int size = 0;

	private int yaw;
	private int yawSin;
	private int yawCos;
	private int pitch;
	private int pitchSin;
	private int pitchCos;

	public void addAlphaModel(AlphaModel m) {
		if (size == models.length) {
			final int newCapacity = (int) ceilPow2(models.length * 2L);
			models = Arrays.copyOf(models, newCapacity);
			states = new AtomicIntegerArray(newCapacity);
		}

		m.asyncSortIdx = size;
		states.set(size, 0);
		models[size] = m;
		size++;
	}

	public void queue(Camera camera) {
		if(frameTimer == null)
			frameTimer = getInjector().getInstance(FrameTimer.class);
		yaw = camera.getFixedYaw();
		yawSin = SINE[yaw];
		yawCos = COSINE[yaw];
		pitch = camera.getFixedPitch();
		pitchSin = SINE[pitch];
		pitchCos = COSINE[pitch];
		queue();
	}

	public void reset() {
		size = 0;
	}

	@Override
	protected void onRun() {
		long start = System.nanoTime();
		synchronized (staticSorter) {
			for (int i = 0; i < size; i++) {
				if (!states.compareAndSet(i, 0, 1))
					continue;
				processModel(staticSorter, models[i]);
			}
		}
		frameTimer.add(Timer.STATIC_ALPHA_SORT, System.nanoTime() - start);
	}

	private void processModel(FacePrioritySorter sorter, AlphaModel m) {
		if(m.lastYaw != yaw || m.lastPitch != pitch || m.lastDist == -1 || abs(m.lastDist - m.dist) > DIST_THRESHOLD) {
			m.lastDist = m.dist;
			m.sortedFacesLen = 0;
			sorter.sortFarStaticModelFacesByDistance(m, yawCos, yawSin, pitchCos, pitchSin);
		}
		m.setSorted(yaw, pitch);
	}

	public boolean forceProcessModelClient(FacePrioritySorter sorter, AlphaModel m) {
		if (m.asyncSortIdx < 0 || m.asyncSortIdx >= size) return false;
		if (states.compareAndSet(m.asyncSortIdx, 0, 1)) {
			processModel(sorter, models[m.asyncSortIdx]);
			return true;
		}
		return false;
	}

	@Override
	protected void onCancel() {}

	@Override
	protected void onReleased() {}
}
