package rs117.hd.scene;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.model.modelreplaceer.types.objects.ModelDefinition;
import rs117.hd.scene.model_overrides.ModelReplacement;
import rs117.hd.utils.HDUtils;

import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Constants.SCENE_SIZE;

@Slf4j
@Singleton
public class AnimatedModelReplacer {
	private static final int SCENE_OFFSET = (EXTENDED_SCENE_SIZE - SCENE_SIZE) / 2;
	private static final long PENDING_TIMEOUT = TimeUnit.SECONDS.toNanos(30);

	private final Client client;
	private final HdPlugin plugin;

	@Inject
	AnimatedModelReplacer(Client client, HdPlugin plugin) {
		this.client = client;
		this.plugin = plugin;
	}

	private final Map<TileObject, Request> requests = new ConcurrentHashMap<>();
	private final Set<Integer> unavailableAnimations = ConcurrentHashMap.newKeySet();

	private static final class Request {
		final Scene scene;
		final ModelReplacement replacement;
		final ModelDefinition definition;
		final Model model;
		final int orientation;
		final int impostorId;
		final long queuedAt = System.nanoTime();
		volatile RuneLiteObject object;

		Request(Scene scene, ModelReplacement replacement, ModelDefinition definition, Model model, int orientation, int impostorId) {
			this.scene = scene;
			this.replacement = replacement;
			this.definition = definition;
			this.model = model;
			this.orientation = orientation;
			this.impostorId = impostorId;
		}
	}

	// Zone uploads run on workers. Only update() registers or removes RuneLite objects.
	public boolean request(
		Scene scene, TileObject source, ModelReplacement replacement, ModelDefinition definition,
		Model model, int orientation, int impostorId, boolean bakedIntoScene
	) {
		if (definition.animationId < 0 || unavailableAnimations.contains(definition.animationId) ||
			replacement.definition != definition)
			return false;

		Request request = requests.get(source);
		if (request == null) {
			request = new Request(scene, replacement, definition, model, orientation, impostorId);
			Request existing = requests.putIfAbsent(source, request);
			if (existing != null)
				request = existing;
		}
		return request.scene == scene && request.replacement == replacement && request.definition == definition &&
			(bakedIntoScene || request.object != null);
	}

	public void update() {
		boolean reloadScene = false;
		for (var entry : requests.entrySet()) {
			TileObject source = entry.getKey();
			Request request = entry.getValue();
			if (request.replacement.definition != request.definition) {
				remove(source, request);
				continue;
			}

			WorldView worldView = source.getWorldView();
			if (worldView == null || worldView.getScene() != request.scene || activeWorldView(worldView) != worldView) {
				if (request.object != null || System.nanoTime() - request.queuedAt > PENDING_TIMEOUT)
					remove(source, request);
				continue;
			}

			if (!isInScene(source, request.scene) || !matchesImpostor(source, request.impostorId)) {
				remove(source, request);
				continue;
			}

			if (request.object != null)
				continue;

			int animationId = request.definition.animationId;
			try {
				if (unavailableAnimations.contains(animationId)) {
					remove(source, request);
					continue;
				}
				var animation = new AnimationController(client, animationId);
				if (animation.getAnimation() == null)
					throw new IllegalArgumentException("Animation not found");

				RuneLiteObject object = client.createRuneLiteObject();
				object.setModel(request.model);
				object.setAnimationController(animation);
				object.setShouldLoop(true);
				object.setOrientation(request.orientation);
				object.setDrawFrontTilesFirst(false);
				object.setLocation(source.getLocalLocation(), source.getPlane());
				object.setZ(source.getZ());
				object.setActive(true);
				request.object = object;
			} catch (RuntimeException ex) {
				log.warn("Unable to animate replacement for object {} with animation {}", source.getId(), animationId, ex);
				unavailableAnimations.add(animationId);
				remove(source, request);
				reloadScene = true;
			}
		}

		if (reloadScene)
			plugin.renderer.reloadScene();
	}

	private WorldView activeWorldView(WorldView worldView) {
		return worldView.getId() == WorldView.TOPLEVEL ? client.getTopLevelWorldView() : client.getWorldView(worldView.getId());
	}

	private boolean matchesImpostor(TileObject source, int impostorId) {
		if (impostorId == -1)
			return true;
		var composition = client.getObjectDefinition(source.getId());
		var impostor = composition != null && composition.getImpostorIds() != null ? composition.getImpostor() : null;
		return (impostor == null ? source.getId() : impostor.getId()) == impostorId;
	}

	private static boolean isInScene(TileObject source, Scene scene) {
		var position = source.getLocalLocation();
		int x = position.getSceneX();
		int y = position.getSceneY();
		if (source instanceof GameObject) {
			var min = ((GameObject) source).getSceneMinLocation();
			x = min.getX();
			y = min.getY();
		}
		var tiles = scene.getExtendedTiles()[source.getPlane()];
		x += SCENE_OFFSET;
		y += SCENE_OFFSET;
		if (x < 0 || x >= tiles.length || y < 0 || y >= tiles[x].length)
			return false;
		for (Tile tile = tiles[x][y]; tile != null; tile = tile.getBridge()) {
			if (HDUtils.getTileObject(tile, source.getHash()) == source)
				return true;
		}
		return false;
	}

	private void remove(TileObject source, Request request) {
		if (requests.remove(source, request) && request.object != null)
			request.object.setActive(false);
	}

	public void clear() {
		requests.forEach(this::remove);
		unavailableAnimations.clear();
	}
}
