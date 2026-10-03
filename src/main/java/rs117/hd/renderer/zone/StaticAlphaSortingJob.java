package rs117.hd.renderer.zone;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;
import lombok.RequiredArgsConstructor;
import rs117.hd.profiling.Profiler;
import rs117.hd.profiling.Timer;
import rs117.hd.renderer.zone.Zone.AlphaModel;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

@RequiredArgsConstructor
public final class StaticAlphaSortingJob extends Job {
	private Profiler profiler;

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
			final int newCapacity = ceilPow2(models.length * 2);
			models = Arrays.copyOf(models, newCapacity);
			states = new AtomicIntegerArray(newCapacity);
		}

		m.asyncSortIdx = size;
		states.set(size, 0);
		models[size] = m;
		size++;
	}

	public void queue(Camera camera) {
		if (profiler == null)
			profiler = getInjector().getInstance(Profiler.class);
		yaw = camera.getFixedYaw();
		yawSin = SINE14[yaw];
		yawCos = COSINE14[yaw];
		pitch = camera.getFixedPitch();
		pitchSin = SINE14[pitch];
		pitchCos = COSINE14[pitch];
		queue();
	}

	public void reset() {
		size = 0;
	}

	@Override
	protected void onRun() {
		long timestamp = profiler.getTimeStamp();
		try (FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			for (int i = 0; i < size; i++) {
				if (!states.compareAndSet(i, 0, 1))
					continue;
				processModel(sorter, models[i]);
			}
		}
		profiler.add(Timer.STATIC_ALPHA_SORT, timestamp);
	}

	private void processModel(FacePrioritySorter sorter, AlphaModel m) {
		m.sortedFacesLen = 0;
		sorter.sortStaticModelFacesByDistance(m, yawCos, yawSin, pitchCos, pitchSin);
		m.setSorted();
	}

	public boolean forceProcessModelClient(AlphaModel m) {
		if (m.asyncSortIdx < 0 || m.asyncSortIdx >= size) return false;
		if (states.compareAndSet(m.asyncSortIdx, 0, 1)) {
			try (FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
				processModel(sorter, models[m.asyncSortIdx]);
			}
			return true;
		}
		return false;
	}
}
