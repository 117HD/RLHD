/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.controller.impl.WintertodtStormController;
import rs117.hd.scene.particles.effector.EffectorDefinitionManager;
import rs117.hd.scene.particles.emitter.EmitterDefinitionManager;

@Slf4j
@Singleton
public class ParticleControllerManager {

	@Inject
	private EventBus eventBus;

	@Inject
	private EmitterDefinitionManager emitterDefinitionManager;

	@Inject
	private EffectorDefinitionManager effectorDefinitions;

	@Inject
	private AreaManager areaManager;

	@Inject
	private WintertodtStormController wintertodtStormController;

	private ParticleManager particleManager;

	private final List<AbstractParticleController> controllers = new ArrayList<>();
	private final Map<String, List<String>> controllerToParticleIds = new LinkedHashMap<>();
	private boolean sceneReady;

	public void startUp(ParticleManager particleManager) {
		this.particleManager = particleManager;
		eventBus.register(this);
		registerController(wintertodtStormController);
		refreshBindings();
		activateControllers();
		log.info("[Particles] Registered {} controller script(s)", controllers.size());
	}

	public void shutDown() {
		deactivateControllers();
		eventBus.unregister(this);
		controllers.clear();
		controllerToParticleIds.clear();
		sceneReady = false;
	}

	public void onEmittersReloaded(@Nullable SceneContext ctx) {
		refreshBindings();
		for (AbstractParticleController controller : controllers) {
			controller.onSceneLoaded(ctx);
		}
	}

	public void update(@Nullable SceneContext ctx, float dt) {
		if (!sceneReady || ctx == null) {
			return;
		}
		for (AbstractParticleController controller : controllers) {
			if (controller.shouldProcess(ctx)) {
				controller.onUpdate(ctx, dt);
			}
		}
	}

	public List<String> getBoundParticleIds(String controllerId) {
		if (controllerId == null) {
			return List.of();
		}
		List<String> ids = controllerToParticleIds.get(controllerId.toUpperCase());
		return ids != null ? ids : List.of();
	}

	public boolean isAnyAreaInView(SceneContext ctx, List<String> areaNames) {
		for (String areaName : areaNames) {
			if (areaName == null || areaName.isEmpty()) {
				continue;
			}
			Area area = areaManager.getArea(areaName);
			if (area == null || area.aabbs == null || area.aabbs.length == 0) {
				continue;
			}
			if (ctx.intersects(area)) {
				return true;
			}
		}
		return false;
	}

	public void addRuntimeEffector(String ownerId, int worldX, int worldY, int plane, String effectorId) {
		effectorDefinitions.addRuntimePlacement(ownerId, worldX, worldY, plane, effectorId);
	}

	public void clearRuntimeEffectors(String ownerId) {
		effectorDefinitions.clearRuntimePlacements(ownerId);
	}

	private void registerController(AbstractParticleController controller) {
		controllers.add(controller);
	}

	private void refreshBindings() {
		controllerToParticleIds.clear();
		Map<String, List<String>> bindings = emitterDefinitionManager.getControllerBindings();
		for (var entry : bindings.entrySet()) {
			controllerToParticleIds.put(entry.getKey().toUpperCase(), List.copyOf(entry.getValue()));
		}
		for (AbstractParticleController controller : controllers) {
			controller.attach(particleManager, this, eventBus);
		}
	}

	private void activateControllers() {
		for (AbstractParticleController controller : controllers) {
			controller.onActivate();
		}
	}

	private void deactivateControllers() {
		for (AbstractParticleController controller : controllers) {
			controller.onDeactivate();
		}
		effectorDefinitions.clearAllRuntimePlacements();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN) {
			sceneReady = false;
		} else if (state.getState() >= GameState.LOGGED_IN.getState()) {
			sceneReady = true;
		}
	}
}
