package rs117.hd.renderer.zone;

import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.model.ModelHasher;
import rs117.hd.model.ModelPusher;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.renderer.Renderer;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.EnvironmentManager;
import rs117.hd.scene.FishingSpotReplacer;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.GroundMaterialManager;
import rs117.hd.scene.LightManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.WaterTypeManager;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.NpcDisplacementCache;

@Slf4j
@Singleton
public class ZoneRenderer implements Renderer {
	private static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private AreaManager areaManager;

	@Inject
	private LightManager lightManager;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private TextureManager textureManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private WaterTypeManager waterTypeManager;

	@Inject
	private GroundMaterialManager groundMaterialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private ModelPusher modelPusher;

	@Inject
	private ModelHasher modelHasher;

	@Inject
	private FishingSpotReplacer fishingSpotReplacer;

	@Inject
	private NpcDisplacementCache npcDisplacementCache;

	@Inject
	private FrameTimer frameTimer;

	@Getter
	private SceneContext sceneContext;
	private SceneContext nextSceneContext;

	@Override
	public void loadScene(WorldView worldView, Scene scene) {
		if (!plugin.isActive())
			return;

		if (plugin.useLowMemoryMode)
			return; // Force scene loading to happen on the client thread

		loadSceneInternal(scene);
	}

	public synchronized void loadSceneInternal(Scene scene) {
		int worldViewId = scene.getWorldViewId();
		if (worldViewId == -1) {
			if (nextSceneContext != null)
				nextSceneContext.destroy();
			nextSceneContext = null;
		} else {
			assert nextSceneContext != null : "Loading sub scene " + worldViewId + " before top-level scene";

		}

		try {
			nextSceneContext = new SceneContext(
				client,
				scene,
				plugin.getExpandedMapLoadingChunks(),
				sceneContext
			);
			proceduralGenerator.generateSceneData(nextSceneContext);
			environmentManager.loadSceneEnvironments(nextSceneContext);
		} catch (OutOfMemoryError oom) {
			log.error(
				"Ran out of memory while loading scene (32-bit: {}, low memory mode: {}, cache size: {})",
				HDUtils.is32Bit(), plugin.useLowMemoryMode, config.modelCacheSizeMiB(), oom
			);
			plugin.displayOutOfMemoryMessage();
			plugin.stopPlugin();
		} catch (Throwable ex) {
			log.error("Error while loading scene:", ex);
			plugin.stopPlugin();
		}
	}

	@Override
	public void swapScene(Scene scene) {
		if (!plugin.isActive() || plugin.skipScene == scene) {
			plugin.redrawPreviousFrame = true;
			return;
		}

		// If the scene wasn't loaded by a call to loadScene, load it synchronously instead
		if (nextSceneContext == null) {
			loadSceneInternal(scene);
			if (nextSceneContext == null)
				return; // Return early if scene loading failed
		}

		lightManager.loadSceneLights(nextSceneContext, sceneContext);
		fishingSpotReplacer.despawnRuneLiteObjects();
		npcDisplacementCache.clear();

		if (sceneContext != null)
			sceneContext.destroy();
		sceneContext = nextSceneContext;
		nextSceneContext = null;
		assert sceneContext != null;

		sceneUploader.prepareBeforeSwap(sceneContext);

		if (sceneContext.intersects(areaManager.getArea("PLAYER_OWNED_HOUSE"))) {
			plugin.isInHouse = true;
			plugin.isInChambersOfXeric = false;
		} else {
			plugin.isInHouse = false;
			plugin.isInChambersOfXeric = sceneContext.intersects(areaManager.getArea("CHAMBERS_OF_XERIC"));
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zoneX, int zoneZ) {
		log.debug("invalidateZone({}, zoneX={}, zoneZ={})", scene, zoneX, zoneZ);
	}

	@Override
	public void despawnWorldView(WorldView worldView) {
		log.debug("despawnWorldView({})", worldView);
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		float cameraX,
		float cameraY,
		float cameraZ,
		float cameraPitch,
		float cameraYaw,
		int minLevel,
		int level,
		int maxLevel,
		Set<Integer> hideRoofIds
	) {
		log.debug(
			"preSceneDraw({}, cameraPos=[{}, {}, {}], cameraOri=[{}, {}], minLevel={}, level={}, maxLevel={}, hideRoofIds=[{}])",
			scene,
			cameraX,
			cameraY,
			cameraZ,
			cameraPitch,
			cameraYaw,
			minLevel,
			level,
			maxLevel,
			hideRoofIds.stream().map(i -> Integer.toString(i)).collect(
				Collectors.joining(", "))
		);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			scene.setDrawDistance(Constants.EXTENDED_SCENE_SIZE);
	}

	@Override
	public void postSceneDraw(Scene scene) {
		log.debug("postSceneDraw({})", scene);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass) {
		log.debug("drawPass({}, {}, pass={})", entityProjection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz) {
		log.debug("drawZoneOpaque({}, {}, zx={}, zz={})", entityProjection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz) {
		log.debug("drawZoneAlpha({}, {}, zx={}, zz={})", entityProjection, scene, zx, zz);
	}

	@Override
	public void drawDynamic(
		Projection worldProjection,
		Scene scene,
		TileObject tileObject,
		Renderable r,
		Model m,
		int orient,
		int x,
		int y,
		int z
	) {
		log.debug(
			"drawDynamic({}, {}, tileObject={}, renderable={}, model={}, orientation={}, modelPos=[{}, {}, {}])",
			worldProjection, scene, tileObject, r, m, orient, x, y, z
		);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m) {
		log.debug("drawTemp({}, {}, gameObject={}, model={})", worldProjection, scene, gameObject, m);
	}

	@Override
	public void draw(int overlaySrgba) {
		log.debug("draw(overlaySrgba={})", overlaySrgba);
	}
}
