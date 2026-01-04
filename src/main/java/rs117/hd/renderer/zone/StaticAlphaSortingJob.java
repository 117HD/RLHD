package rs117.hd.renderer.zone;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicIntegerArray;
import lombok.RequiredArgsConstructor;
import rs117.hd.renderer.zone.Zone.AlphaModel;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.renderer.zone.Zone.AlphaModel.SORT_TYPE_FAR;
import static rs117.hd.utils.HDUtils.ceilPow2;

@RequiredArgsConstructor
public final class StaticAlphaSortingJob extends Job {
	private final FacePrioritySorter staticSorter;

	private AlphaModel[] models = new AlphaModel[16];
	private AtomicIntegerArray states = new AtomicIntegerArray(16);
	private int size = 0;

	private int yawSin;
	private int yawCos;
	private int pitchSin;
	private int pitchCos;

	public void addAlphaModel(AlphaModel m, boolean farZone) {
		if (size == models.length) {
			final int newCapacity = (int) ceilPow2(models.length * 2L);
			models = Arrays.copyOf(models, newCapacity);
			states = new AtomicIntegerArray(newCapacity);
		}

		m.asyncSortIdx = size;
		if(farZone) m.flags |= SORT_TYPE_FAR;
		states.set(size, 0);
		models[size] = m;
		size++;
	}

	public void queue(Camera camera) {
		yawSin = SINE[camera.getFixedYaw()];
		yawCos = COSINE[camera.getFixedYaw()];
		pitchSin = SINE[camera.getFixedPitch()];
		pitchCos = COSINE[camera.getFixedPitch()];
		queue();
	}

	public void reset() {
		size = 0;
	}

	@Override
	protected void onRun() {
		synchronized (staticSorter) {
			for (int i = 0; i < size; i++) {
				if (!states.compareAndSet(i, 0, 1))
					continue;
				processModel(staticSorter, models[i]);
			}
		}
	}

	private void processModel(FacePrioritySorter sorter, AlphaModel m) {
		if((m.flags & SORT_TYPE_FAR) != 0) {
			sorter.sortFarStaticModelFaces(m, yawCos, yawSin, pitchCos, pitchSin);
		} else {
			sorter.sortStaticModelFaces(m, yawCos, yawSin, pitchCos, pitchSin);
		}
		m.setSorted();
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
