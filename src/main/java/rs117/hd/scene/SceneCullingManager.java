package rs117.hd.scene;

import com.google.inject.Inject;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.utils.Camera;
import rs117.hd.utils.DebugDraw;
import rs117.hd.utils.collections.ConcurrentPool;
import rs117.hd.utils.jobs.Job;

import static rs117.hd.utils.MathUtils.*;

@Singleton
@Slf4j
public class SceneCullingManager {
	private static final int SCRATCH_SIZE = 32;

	private final ConcurrentPool<CullingJob> CULLING_JOB_POOL = new ConcurrentPool<>(CullingJob::new);
	private final ConcurrentPool<CullingSphere> CULLING_SPHERE_POOL = new ConcurrentPool<>(CullingSphere::new);
	private final ConcurrentPool<CullingAABB> CULLING_AABB_POOL = new ConcurrentPool<>(CullingAABB::new);

	private final List<CullingResult> pendingCullingResults = new ArrayList<>();
	private final List<Camera> cullingCameras = new ArrayList<>();
	private final float[] debugProjected = new float[4];
	private final float[] debugScratch = new float[SCRATCH_SIZE];

	@Inject
	private Client client;

	public void addCamera(Camera camera) {
		assert cullingCameras.size() < 8 : "SceneCullingManager supports at most 8 cameras";
		if(!cullingCameras.contains(camera))
			cullingCameras.add(camera);
	}

	public void removeCamera(Camera camera) {
		cullingCameras.remove(camera);
	}

	public synchronized CullingSphere obtainSphere(float x, float y, float z, float radius) {
		final CullingSphere result = CULLING_SPHERE_POOL.acquire();
		result.x = x;
		result.y = y;
		result.z = z;
		result.radius = radius;

		return result;
	}

	public synchronized CullingAABB obtainBox(
		float minX,
		float minY,
		float minZ,
		float maxX,
		float maxY,
		float maxZ
	) {
		if (minX > maxX) {
			float t = minX;
			minX = maxX;
			maxX = t;
		}

		if (minY > maxY) {
			float t = minY;
			minY = maxY;
			maxY = t;
		}

		if (minZ > maxZ) {
			float t = minZ;
			minZ = maxZ;
			maxZ = t;
		}

		final CullingAABB result = CULLING_AABB_POOL.acquire();

		result.x = (minX + maxX) * 0.5f;
		result.y = (minY + maxY) * 0.5f;
		result.z = (minZ + maxZ) * 0.5f;

		result.extentsX = (maxX - minX) * 0.5f;
		result.extentsY = (maxY - minY) * 0.5f;
		result.extentsZ = (maxZ - minZ) * 0.5f;

		return result;
	}

	public void flush() {
		if(pendingCullingResults.isEmpty() || cullingCameras.isEmpty())
			return;

		CullingJob job = CULLING_JOB_POOL.acquire();
		job.id++;
		job.cullingCameras.addAll(cullingCameras);

		for (int i = 0; i < pendingCullingResults.size(); i++) {
			final CullingResult result = pendingCullingResults.get(i);
			result.job = job;
			job.pendingCullingResults.add(result);
		}
		pendingCullingResults.clear();

		job.queue();
	}

	public void debugDraw(CullingResult... results) {
		for(int i = 0; i < results.length; i++)
			debugDraw(results[i]);
	}

	public void debugDraw(CullingResult result) {
		if(result == null)
			return;

		result.build(debugProjected, debugScratch);
		result.debugDraw(debugScratch);
	}

	public class CullingJob extends Job {
		private final List<CullingResult> pendingCullingResults = new ArrayList<>();
		private final List<Camera> cullingCameras = new ArrayList<>();
		private final float[] projected = new float[4];
		private final float[] scratch = new float[SCRATCH_SIZE];
		private int id;

		@Override
		protected void onRun() {
			for (int i = 0; i < pendingCullingResults.size(); i++) {
				CullingResult result = pendingCullingResults.get(i);
				if (result == null)
					continue;

				result.build(projected, scratch);

				byte newFlags = 0;
				for (int camIdx = 0; camIdx < cullingCameras.size(); camIdx++) {
					if (result.test(cullingCameras.get(camIdx), scratch))
						newFlags |= (byte) (1 << camIdx);
				}

				result.visibilityFlags = newFlags;
			}

			pendingCullingResults.clear();
			cullingCameras.clear();

			CULLING_JOB_POOL.recycle(this);
		}
	}

	public class CullingAABB extends CullingResult {
		public float extentsX;
		public float extentsY;
		public float extentsZ;

		@Override
		protected void build(float[] p, float[] scratch) {
			float worldX = offsetX + x;
			float worldY = offsetY + y;
			float worldZ = offsetZ + z;

			if (projection == null) {
				scratch[0] = worldX - extentsX;
				scratch[1] = worldY - extentsY;
				scratch[2] = worldZ - extentsZ;
				scratch[3] = worldX + extentsX;
				scratch[4] = worldY + extentsY;
				scratch[5] = worldZ + extentsZ;
				return;
			}

			float minX = Float.POSITIVE_INFINITY;
			float minY = Float.POSITIVE_INFINITY;
			float minZ = Float.POSITIVE_INFINITY;

			float maxX = Float.NEGATIVE_INFINITY;
			float maxY = Float.NEGATIVE_INFINITY;
			float maxZ = Float.NEGATIVE_INFINITY;

			for (int ix = -1; ix <= 1; ix += 2) {
				for (int iy = -1; iy <= 1; iy += 2) {
					for (int iz = -1; iz <= 1; iz += 2) {
						projection.project(
							worldX + ix * extentsX,
							worldY + iy * extentsY,
							worldZ + iz * extentsZ,
							p
						);

						minX = min(minX, p[0]);
						minY = min(minY, p[1]);
						minZ = min(minZ, p[2]);

						maxX = max(maxX, p[0]);
						maxY = max(maxY, p[1]);
						maxZ = max(maxZ, p[2]);
					}
				}
			}

			scratch[0] = minX;
			scratch[1] = minY;
			scratch[2] = minZ;

			scratch[3] = maxX;
			scratch[4] = maxY;
			scratch[5] = maxZ;
		}

		@Override
		protected boolean test(Camera camera, float[] scratch) {
			return camera.intersectsAABB(scratch[0], scratch[1], scratch[2], scratch[3], scratch[4], scratch[5]);
		}

		@Override
		protected void debugDraw(float[] scratch) {
			DebugDraw.drawMinMax(scratch[0], scratch[1], scratch[2], scratch[3], scratch[4], scratch[5], isVisible() ? Color.GREEN : Color.RED, false);
		}

		@Override
		public void release() {
			super.release();
			CULLING_AABB_POOL.recycle(this);
		}
	}

	public class CullingSphere extends CullingResult {
		public float radius;

		@Override
		protected void build(float[] p, float[] scratch) {
			float worldX = offsetX + x;
			float worldY = offsetY + y;
			float worldZ = offsetZ + z;

			if(projection == null) {
				scratch[0] = worldX;
				scratch[1] = worldY;
				scratch[2] = worldZ;
				scratch[3] = radius;
				return;
			}

			projection.project(worldX, worldY, worldZ, p);
			scratch[0] = p[0];
			scratch[1] = p[0];
			scratch[2] = p[0];

			projection.project(worldX + radius, worldY + radius, worldZ + radius, p);
			scratch[3] = max(
				abs(p[0] - scratch[0]),
				max(
					abs(p[1] - scratch[1]),
					abs(p[2] - scratch[2])
				)
			);
		}

		@Override
		protected boolean test(Camera camera, float[] scratch) {
			return camera.intersectsSphere( scratch[0], scratch[1], scratch[2], scratch[3]);
		}

		@Override
		protected void debugDraw(float[] scratch) {
			DebugDraw.drawSphere(scratch[0], scratch[1], scratch[2], scratch[3], isVisible() ? Color.GREEN : Color.RED, false);
		}

		@Override
		public void release() {
			super.release();
			CULLING_SPHERE_POOL.recycle(this);
		}
	}

	public abstract class CullingResult {
		@Getter
		private byte visibilityFlags = 0;

		protected CullingJob job;
		public Projection projection;

		public float x, y, z;
		public float offsetX, offsetY, offsetZ;
		protected int cullingJobId;

		protected abstract void build(float[] p, float[] scratch);
		protected abstract boolean test(Camera camera, float[] scratch);
		protected abstract void debugDraw(float[] scratch);

		private void ensureJobCompletion() {
			if(job == null)
				return;

			if(cullingJobId == job.id)
				job.waitForCompletion();
			job = null;
		}

		public boolean isVisible() {
			ensureJobCompletion();
			return visibilityFlags != 0;
		}

		public boolean isVisible(Camera camera) {
			ensureJobCompletion();
			return (visibilityFlags & camera.getCullingMask()) != 0;
		}

		public void queue() {
			assert !pendingCullingResults.contains(this);
			pendingCullingResults.add(this);

			if(pendingCullingResults.size() >= HdPlugin.PROCESSOR_COUNT)
				flush();
		}

		public void release() {
			x = y = z = offsetX = offsetY = offsetZ = 0;
			visibilityFlags = 0;
			projection = null;
			job = null;
		}
	}
}