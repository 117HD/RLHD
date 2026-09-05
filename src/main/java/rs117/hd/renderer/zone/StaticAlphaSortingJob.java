package rs117.hd.renderer.zone;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import lombok.RequiredArgsConstructor;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.Zone.AlphaModel;
import rs117.hd.utils.Camera;
import rs117.hd.utils.collections.PooledArrayType;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;

@RequiredArgsConstructor
final class StaticAlphaSortingJob extends Job {
	private static final int UNSORTED = 0;
	private static final int SORTING = 1;
	private static final int SORTED = 2;
	private static final AtomicIntegerFieldUpdater<AlphaModel> SORTING_STATE =
		AtomicIntegerFieldUpdater.newUpdater(AlphaModel.class, "sortingState");

	private FrameTimer frameTimer;

	private final ArrayDeque<AlphaModel> models = new ArrayDeque<>();
	private boolean sorting;

	private int yaw;
	private int yawSin;
	private int yawCos;
	private int pitch;
	private int pitchSin;
	private int pitchCos;

	synchronized void addAlphaModel(AlphaModel m) {
		m.sortingState = UNSORTED;
		models.add(m);
	}

	void queue(Camera camera) {
		if (frameTimer == null)
			frameTimer = getInjector().getInstance(FrameTimer.class);
		yaw = camera.getFixedYaw();
		yawSin = SINE14[yaw];
		yawCos = COSINE14[yaw];
		pitch = camera.getFixedPitch();
		pitchSin = SINE14[pitch];
		pitchCos = COSINE14[pitch];
		synchronized (this) {
			sorting = true;
		}
		queue();
	}

	synchronized void reset() {
		models.clear();
	}

	void queueAdditionalModels(List<AlphaModel> candidates, Camera camera) {
		boolean shouldQueue;
		synchronized (this) {
			boolean added = false;
			for (int i = 0; i < candidates.size(); i++) {
				AlphaModel m = candidates.get(i);
				if ((m.flags & AlphaModel.SKIP) != 0 || m.isTemp() || m.tempSortedFaces != null)
					continue;

				m.tempSortedFaces = PooledArrayType.INT.borrow((m.packedFaces.length + m.doubleSidedCount) * 3);
				m.sortingState = UNSORTED;
				models.add(m);
				added = true;
			}
			if (!added)
				return;

			shouldQueue = !sorting;
			sorting = true;
		}
		if (shouldQueue)
			queue(camera);
	}

	@Override
	protected void onRun() {
		long start = System.nanoTime();
		try (FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			while (true) {
				AlphaModel m;
				synchronized (this) {
					m = models.poll();
					if (m == null) {
						sorting = false;
						break;
					}
				}
				if (SORTING_STATE.compareAndSet(m, UNSORTED, SORTING))
					processModel(sorter, m);
			}
		} catch (RuntimeException | Error ex) {
			synchronized (this) {
				sorting = false;
			}
			throw ex;
		}
		frameTimer.add(Timer.STATIC_ALPHA_SORT, System.nanoTime() - start);
	}

	private void processModel(FacePrioritySorter sorter, AlphaModel m) {
		m.sortedFacesLen = 0;
		sorter.sortStaticModelFacesByDistance(m, yawCos, yawSin, pitchCos, pitchSin);
		m.setSorted();
		m.sortingState = SORTED;
	}

	public boolean forceProcessModelClient(AlphaModel m) {
		if (!SORTING_STATE.compareAndSet(m, UNSORTED, SORTING))
			return false;

		try (FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			processModel(sorter, m);
		}
		return true;
	}
}
