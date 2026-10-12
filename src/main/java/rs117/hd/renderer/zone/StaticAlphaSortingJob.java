package rs117.hd.renderer.zone;

import java.util.Arrays;
import javax.inject.Inject;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.Zone.AlphaModel;
import rs117.hd.utils.Camera;
import rs117.hd.utils.jobs.Job;

import static net.runelite.api.Perspective.*;
import static rs117.hd.utils.MathUtils.*;

public final class StaticAlphaSortingJob extends Job {

	@Inject
	private FrameTimer frameTimer;

	private AlphaModel[] models = new AlphaModel[16];
	private int[] sortedAlphaIndicies;
	private int size = 0;

	private Camera camera;
	private int zx, zz;
	private int minBoundX, minBoundY, minBoundZ, maxBoundX, maxBoundY, maxBoundZ;
	private boolean frustumCull;

	public StaticAlphaSortingJob() { super(true); }

	public void addAlphaModel(AlphaModel m) {
		if (size == models.length) {
			final int newCapacity = ceilPow2(models.length * 2);
			models = Arrays.copyOf(models, newCapacity);
		}

		m.asyncSortIdx = size;
		models[size] = m;
		size++;

		final int modelX = ((zx - m.zofx) << 10) + m.x;
		final int modelZ = ((zz - m.zofz) << 10) + m.z;
		minBoundX = min(minBoundX, modelX + m.minX);
		maxBoundX = max(maxBoundX, modelX + m.maxX);
		minBoundY = min(minBoundY, m.y + m.minY);
		maxBoundY = max(maxBoundY, m.y + m.maxY);
		minBoundZ = min(minBoundZ, modelZ + m.minZ);
		maxBoundZ = max(maxBoundZ, modelZ + m.maxZ);
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

		// Classify the combined AABB of every alpha model against the frustum, so we can skip
		// per-model culling entirely when the zone's alpha geometry is fully visible.
		final int boundsClassification = frustumCull ?
			camera.classifyAABB(minBoundX, minBoundY, minBoundZ, maxBoundX, maxBoundY, maxBoundZ) : 0;

		try (FacePrioritySorter sorter = FacePrioritySorter.POOL.acquire()) {
			for (int i = 0; i < size; i++) {
				final AlphaModel m = models[i];
				m.sortedIndiciesCount = 0;

				if (!frustumCull || boundsClassification == 1) {
					// Fully visible, so every model in the zone is visible
					m.setVisible();
				} else if (boundsClassification == 0) {
					// Partially visible, so cull each model individually
					final int modelX = ((zx - m.zofx) << 10) + m.x;
					final int modelZ = ((zz - m.zofz) << 10) + m.z;
					if (camera.intersectsAABB(
						modelX + m.minX, m.y + m.minY, modelZ + m.minZ,
						modelX + m.maxX, m.y + m.maxY, modelZ + m.maxZ
					)) {
						m.setVisible();
					}
				}

				if (m.isVisible()) {
					sorter.sortStaticModelFacesByDistance(sortedAlphaIndicies, m, yawCos, yawSin, pitchCos, pitchSin);
					m.setSorted();
				}

				m.calculateDist(cx, cy, cz, zx, zz);
			}
		}
		frameTimer.add(Timer.STATIC_ALPHA_SORT, System.nanoTime() - start);
	}
}
