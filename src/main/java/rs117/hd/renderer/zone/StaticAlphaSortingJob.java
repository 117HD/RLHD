package rs117.hd.renderer.zone;

import java.util.Arrays;
import javax.inject.Inject;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

public final class StaticAlphaSortingJob extends Job {

	@Inject
	private FrameTimer frameTimer;

	private AlphaModelInstance[] models = new AlphaModelInstance[16];
	private int[] sortedAlphaIndicies;
	private int size = 0;

	private Camera camera;
	private int zx, zz;
	private int minBoundX, minBoundY, minBoundZ, maxBoundX, maxBoundY, maxBoundZ;
	private boolean frustumCull;

	public StaticAlphaSortingJob() { super(true); }

	public void addAlphaModel(AlphaModelInstance m) {
		if (size == models.length) {
			final int newCapacity = ceilPow2(models.length * 2);
			models = Arrays.copyOf(models, newCapacity);
		}

		m.asyncSortIdx = size;
		models[size] = m;
		size++;

		final int modelX = ((zx - m.zofx) << 10) + m.x;
		final int modelZ = ((zz - m.zofz) << 10) + m.z;
		minBoundX = min(minBoundX, modelX - m.extentsX);
		maxBoundX = max(maxBoundX, modelX + m.extentsX);
		minBoundY = min(minBoundY, m.y - m.extentsY);
		maxBoundY = max(maxBoundY, m.y + m.extentsY);
		minBoundZ = min(minBoundZ, modelZ - m.extentsZ);
		maxBoundZ = max(maxBoundZ, modelZ + m.extentsZ);
	}

	public void setup(Camera camera, int zx, int zz, boolean frustumCull){
		this.camera = camera;
		this.zx = zx;
		this.zz = zz;
		this.frustumCull = frustumCull;
	}

	public void queue(int[] sortedAlphaIndicies) {
		if(size == 0)
			return;
		this.sortedAlphaIndicies = sortedAlphaIndicies;
		super.queue();
	}

	public void reset() {
		Arrays.fill(models, 0, size, null);
		size = 0;
		minBoundX = minBoundY = minBoundZ = Integer.MAX_VALUE;
		maxBoundX = maxBoundY = maxBoundZ = Integer.MIN_VALUE;
	}

	@Override
	protected void onRun() {
		final long start = System.nanoTime();

		final int cx = (int) camera.getPositionX();
		final int cy = (int) camera.getPositionY();
		final int cz = (int) camera.getPositionZ();
		final int yaw = camera.getFixedYaw();
		final int yawSin = SINE14[yaw];
		final int yawCos = COSINE14[yaw];
		final int pitch = camera.getFixedPitch();
		final int pitchSin = SINE14[pitch];
		final int pitchCos = COSINE14[pitch];

		// Face order depends only on camera orientation, so this identifies a StaticAlphaModel's cached sort
		final int sortKey = yaw << 16 | pitch;

		// Classify the combined AABB of every alpha model against the frustum, so we can skip
		// per-model culling entirely when the zone's alpha geometry is fully visible.
		final int boundsClassification = frustumCull ?
			camera.classifyAABB(minBoundX, minBoundY, minBoundZ, maxBoundX, maxBoundY, maxBoundZ) : 1;

		final boolean fullyOutside = boundsClassification == -1;
		final boolean partiallyVisible = boundsClassification == 0;

		try (FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			for (int i = 0; i < size; i++) {
				final AlphaModelInstance m = models[i];
				m.sortedIndiciesCount = 0;

				if (fullyOutside) {
					m.setHidden();
				} else if (partiallyVisible) {
					final int modelX = ((zx - m.zofx) << 10) + m.x;
					final int modelZ = ((zz - m.zofz) << 10) + m.z;
					if (!camera.intersectsAABB(
						modelX - m.extentsX, m.y - m.extentsY, modelZ - m.extentsZ,
						modelX + m.extentsX, m.y + m.extentsY, modelZ + m.extentsZ
					)) {
						// Alpha Models always start as visible to avoid pop-in, so we mark them as hidden since we know they're not visible
						m.setHidden();
					}
				}

				if (m.isVisible()) {
					// Sorts the shared StaticAlphaModel if its cached order is stale, then writes this instance's indices
					sorter.sortStaticModelFacesByDistance(sortedAlphaIndicies, m, sortKey, yawCos, yawSin, pitchCos, pitchSin);
					m.calculateDist(cx, cy, cz, zx, zz);
					m.setSorted();
				}
			}
		}
		frameTimer.add(Timer.STATIC_ALPHA_SORT, System.nanoTime() - start);
	}
}