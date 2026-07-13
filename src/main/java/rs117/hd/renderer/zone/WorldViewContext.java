package rs117.hd.renderer.zone;

import com.google.inject.Injector;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.uniforms.UBOWorldViews;
import rs117.hd.opengl.uniforms.UBOWorldViews.WorldViewStruct;
import rs117.hd.scene.DisplacementManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.jobs.JobGroup;

import static net.runelite.api.Constants.*;
import static rs117.hd.renderer.zone.FrameContext.VAO_COUNT;
import static rs117.hd.renderer.zone.SceneManager.NUM_ZONES;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.collections.Util.quickSort;

@Slf4j
public class WorldViewContext {
	@Inject
	private Injector injector;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private SceneManager sceneManager;

	@Inject
	private ZoneRenderer zoneRenderer;

	@Inject
	private DisplacementManager displacementManager;

	final int worldViewId;
	final int sizeX, sizeZ;
	@Nullable
	WorldViewStruct uboWorldViewStruct;
	ZoneSceneContext sceneContext;
	Zone[][] zones;
	boolean isLoading = true;

	boolean isBoat = false;
	rs117.hd.scene.areas.AABB boatAABB = null;

	int minLevel, level, maxLevel;
	Set<Integer> hideRoofIds;

	private final Comparator<Zone> alphaSortComparator = Comparator.comparingInt((Zone z) -> z.dist).reversed();
	private final List<Zone> alphaZones = new ArrayList<>();

	private final int[] dynamicDrawRanges = new int[VAO_COUNT * 2];

	CommandBuffer vaoSceneCmd;
	CommandBuffer vaoDirectionalCmd;

	public long loadTime;
	public long uploadTime;
	public long sceneSwapTime;

	final JobGroup<ZoneUploadJob> sceneLoadGroup = new JobGroup<>(true, true);
	final JobGroup<ZoneUploadJob> streamingGroup = new JobGroup<>(false, false);
	final JobGroup<ZoneUploadJob> invalidationGroup = new JobGroup<>(true, false);

	WorldViewContext(
		@Nullable WorldView worldView,
		@Nullable ZoneSceneContext sceneContext,
		UBOWorldViews uboWorldViews
	) {
		this.worldViewId = worldView == null ? WorldView.TOPLEVEL : worldView.getId();
		this.sceneContext = sceneContext;
		this.sizeX = worldView == null ? NUM_ZONES : worldView.getSizeX() >> 3;
		this.sizeZ = worldView == null ? NUM_ZONES : worldView.getSizeY() >> 3;
		if (worldView != null)
			uboWorldViewStruct = uboWorldViews.acquire(worldView);
		zones = new Zone[sizeX][sizeZ];
	}

	public void initialize(Injector injector) {
		injector.injectMembers(this);

		vaoSceneCmd = new CommandBuffer("WorldViewScene");
		vaoDirectionalCmd = new CommandBuffer("WorldViewDirectional");

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z] = injector.getInstance(Zone.class);
	}

	void sortStaticAlphaModels(Camera camera) {
		alphaZones.clear();

		final int offset = sceneContext.sceneOffset >> 3;
		final int camPosX = (int) camera.getPositionX();
		final int camPosZ = (int) camera.getPositionZ();
		for (int zx = 0; zx < sizeX; zx++) {
			for (int zz = 0; zz < sizeZ; zz++) {
				final Zone z = zones[zx][zz];
				if (z.alphaModels.isEmpty() || (worldViewId == WorldView.TOPLEVEL && !z.inSceneFrustum))
					continue;

				final int dx = camPosX - ((zx - offset) << 10);
				final int dz = camPosZ - ((zz - offset) << 10);
				z.dist = dx * dx + dz * dz;
				alphaZones.add(z);
			}
		}

		if (!alphaZones.isEmpty()) {
			quickSort(alphaZones, alphaSortComparator);
			for (int i = 0; i < alphaZones.size(); i++)
				alphaZones.get(i).alphaStaticModelSort(camera);
		}
	}

	void resetDrawRanges() {
		for (int i = 0; i < VAO_COUNT; i++)
			dynamicDrawRanges[i * 2] = dynamicDrawRanges[i * 2 + 1] = 0;
	}

	synchronized void trackDrawRange(DynamicModelVAO.View view, int type) {
		dynamicDrawRanges[type * 2] = min(dynamicDrawRanges[type * 2], view.getDrawIdx());
		dynamicDrawRanges[type * 2 + 1] = max(dynamicDrawRanges[type * 2 + 1], view.getDrawIdx() + 1);
	}

	DynamicModelVAO.View beginDraw(int type, int faces) {
		FrameContext ctx = zoneRenderer.frameContext();
		DynamicModelVAO.View view = ctx.dynamicModelVaos[type].beginDraw(faces);
		trackDrawRange(view, type);
		return view;
	}

	DynamicModelVAO.View beginDraw(int type, int playerDrawIndex, int faces) {
		FrameContext ctx = zoneRenderer.frameContext();
		DynamicModelVAO.View view = ctx.dynamicModelVaos[type].beginDraw(playerDrawIndex, faces);
		trackDrawRange(view, type);
		return view;
	}

	void drawAll(int type, CommandBuffer cmd) {
		FrameContext ctx = zoneRenderer.frameContext();
		ctx.dynamicModelVaos[type].draw(cmd, dynamicDrawRanges[type * 2], dynamicDrawRanges[type * 2 + 1]);
	}

	void handleZoneSwap(int zx, int zz, boolean queue) {
		Zone curZone = zones[zx][zz];
		ZoneUploadJob uploadTask = curZone.uploadJob;
		if (uploadTask == null)
			return;

		if (uploadTask.isDone()) {
			curZone.uploadJob = null;
			if (uploadTask.ranToCompletion() && !uploadTask.wasCancelled()) {
				log.trace("swapping zone({}): [{}-{},{}]", uploadTask.zone.hashCode(), worldViewId, zx, zz);

				Zone prevZone = curZone;
				// Swap the zone out with the one we just uploaded
				zones[zx][zz] = curZone = uploadTask.zone;
				clientThread.invoke(curZone::unmap);

				if (prevZone != curZone) {
					curZone.inSceneFrustum = prevZone.inSceneFrustum;
					curZone.inShadowFrustum = prevZone.inShadowFrustum;
					DestructibleHandler.queueDestruction(prevZone);
				}

				sceneContext.animatedDynamicObjectIds.addAll(curZone.animatedDynamicObjectIds);
			} else if (uploadTask.wasCancelled() && !curZone.cull) {
				boolean shouldRetry = uploadTask.encounteredError() && curZone.isFirstLoadingAttempt;
				if (shouldRetry) {
					// Cache if the previous upload task encountered an error,
					// if it encounters another one the zone will be dropped to avoid constantly rebuilding
					curZone.rebuild = true;
					curZone.isFirstLoadingAttempt = false;
				} else if (uploadTask.encounteredError()) {
					log.debug(
						"Zone({}) [{}-{},{}] was cancelled due to an error, dropping to avoid constantly rebuilding",
						curZone.hashCode(),
						worldViewId,
						zx,
						zz
					);
				}
			}
			uploadTask.release();
		}
	}

	void processZoneSwaps() {
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				handleZoneSwap(x, z, true);

				final Zone zone = zones[x][z];
				if(zone.fadingAlpha <= 0)
					continue;

				zone.fadingAlpha = max(0.0f, zone.fadingAlpha - plugin.deltaTime);
				zone.setMetadata(this, sceneContext, x, z);
			}
		}
	}

	void processZoneRebuilds() {
		for (int x = 0; x < sizeX; x++) {
			for (int z = 0; z < sizeZ; z++) {
				if (zones[x][z].rebuild) {
					zones[x][z].rebuild = false;
					invalidateZone(x, z);
				}
			}
		}
	}

	void completeInvalidation() {
		if (invalidationGroup.getPendingCount() <= 0)
			return;

		invalidationGroup.complete();

		for (int x = 0; x < sizeX; x++)
			for (int z = 0; z < sizeZ; z++)
				handleZoneSwap(x, z, false);
	}

	void free() {
		sceneLoadGroup.cancel();
		streamingGroup.cancel();

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = null;

		if (uboWorldViewStruct != null)
			uboWorldViewStruct.free();
		uboWorldViewStruct = null;

		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				zones[x][z].destroy();

		isLoading = true;
	}

	void invalidate() {
		log.debug("invalidate all zones for worldViewId: [{}]", worldViewId);
		for (int x = 0; x < sizeX; ++x)
			for (int z = 0; z < sizeZ; ++z)
				invalidateZone(x, z);
	}

	void invalidateZone(int zx, int zz) {
		Zone curZone = zones[zx][zz];
		if (curZone.uploadJob != null) {
			Zone pendingZone = curZone.uploadJob.zone;
			log.trace(
				"Invalidate Zone({}) - Cancelled upload task: [{}-{},{}] task zone({})",
				curZone.hashCode(),
				worldViewId,
				zx,
				zz,
				pendingZone.hashCode()
			);
			curZone.uploadJob.cancel();
			curZone.uploadJob.release();

			if (pendingZone != curZone)
				DestructibleHandler.destroy(pendingZone);
		}

		Zone newZone = injector.getInstance(Zone.class);
		newZone.dirty = zones[zx][zz].dirty;

		curZone.uploadJob = ZoneUploadJob.build(this, sceneContext, newZone, false, zx, zz);
		curZone.uploadJob.queue(invalidationGroup, sceneManager.getGenerateSceneDataTask());
	}

	void buildBoatDisplacement() {
		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		for (int z = 0; z < MAX_Z; z++) {
			for (int x = 0; x < sceneContext.sizeX; x++) {
				for (int y = 0; y < sceneContext.sizeZ; y++) {
					final Tile tile = tiles[z][x][y];
					if (tile == null)
						continue;

					GameObject[] gameObjects = tile.getGameObjects();
					for (int g = 0; g < gameObjects.length; g++) {
						GameObject gameObject = gameObjects[g];
						if(gameObject == null)
							continue;

						Renderable renderable = gameObject.getRenderable();
						if(renderable == null)
							continue;

						if(displacementManager.boatIds.contains(gameObject.getId())) {
							Model model = null;
							if(renderable instanceof Model) {
								model = (Model) renderable;
							} else if(renderable instanceof DynamicObject) {
								model = ((DynamicObject) renderable).getModelZbuf();
							}

							if ( model != null) {
								final var modelAABB = model.getAABB(0);

								final int centerX = gameObject.getX() + modelAABB.getCenterX();
								final int centerY = gameObject.getZ() + modelAABB.getCenterY();
								final int centerZ = gameObject.getY() + modelAABB.getCenterZ();

								isBoat = true;
								boatAABB = new AABB(
									centerX - modelAABB.getExtremeX(),
									centerY - modelAABB.getExtremeY(),
									centerZ - modelAABB.getExtremeZ(),

									centerX + modelAABB.getExtremeX(),
									centerY + modelAABB.getExtremeY(),
									centerZ + modelAABB.getExtremeZ()
								);
								return;
							}
						}
					}
				}
			}
		}
	}
}
