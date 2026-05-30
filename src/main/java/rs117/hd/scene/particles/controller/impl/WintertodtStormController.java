/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.controller.impl;

import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import net.runelite.api.GameObject;
import net.runelite.api.Tile;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.controller.AbstractParticleController;
import rs117.hd.scene.particles.controller.ParticleModifier;
import rs117.hd.scene.particles.effector.EffectorBuilder;
import rs117.hd.scene.particles.effector.EffectorRef;

@Singleton
public class WintertodtStormController extends AbstractParticleController {

	public static final EffectorRef STORM = EffectorRef.json("WINTERTODT_STORM");

	public static final EffectorRef STORM_WIND = EffectorBuilder.create("WINTERTODT_STORM_WIND")
		.radiusTiles(4f)
		.heightOffset(600)
		.wind(9000f, 400f, -1900f, 2.25f, 1.0f, 0f, 12f)
		.edgeFalloff(true)
		.falloffPower(1.0f)
		.buildRef();

	private static final int STORM_OBJECT_ID = 29308;
	private static final int STORM_VORTEX_X = 1630;
	private static final int STORM_VORTEX_Y = 4009;
	private static final int STORM_VORTEX_PLANE = 0;
	private static final int STORM_PLANE = 0;

	private static final int[][] STORM_WIND_PLACEMENTS = {
		{ 1620, 3996, STORM_PLANE },
		{ 1641, 3997, STORM_PLANE },
	};

	private static final float CALM_SPEED_MIN = 524288f;
	private static final float CALM_SPEED_MAX = 1048576f;

	private static final float[] SNOW_COLOR_MIN = { 189 / 255f, 212 / 255f, 245 / 255f, 238 / 255f };
	private static final float[] SNOW_COLOR_MAX = { 216 / 255f, 232 / 255f, 252 / 255f, 1f };
	private static final float[] SNOW_COLOR_TARGET = { 155 / 255f, 184 / 255f, 216 / 255f, 204 / 255f };

	private static final ParticleModifier STORM_MODIFIER = ParticleModifier.create()
		.speed(CALM_SPEED_MIN * 2f, CALM_SPEED_MAX * 2.5f)
		.weatherDensity(2.5f)
		.colorRange(SNOW_COLOR_MIN, SNOW_COLOR_MAX)
		.targetColor(SNOW_COLOR_TARGET, 25f, 25f)
		.localEffectorFilter(STORM)
		.globalEffectors(STORM_WIND);

	private static final ParticleModifier CALM_MODIFIER = ParticleModifier.create()
		.speed(CALM_SPEED_MIN, CALM_SPEED_MAX)
		.weatherDensity(0.1f)
		.colorRange(SNOW_COLOR_MIN, SNOW_COLOR_MAX)
		.targetColor(SNOW_COLOR_TARGET, 25f, 25f);

	private int stormObjectCount;
	@Nullable
	private Boolean lastStormState;

	@Override
	public String getName() {
		return "wintertodt_storm";
	}

	@Override
	public List<String> getViewAreas() {
		return List.of("WINTERTODT_ARENA");
	}

	@Override
	public void onActivate() {
		super.onActivate();
		resetStormState();
	}

	@Override
	public void onDeactivate() {
		resetStormState();
		super.onDeactivate();
	}

	@Override
	public void onSceneLoaded(@Nullable SceneContext ctx) {
		resetStormState();
		if (ctx == null || ctx.scene == null) {
			return;
		}
		for (Tile[][] plane : ctx.scene.getExtendedTiles()) {
			for (Tile[] column : plane) {
				if (column == null) {
					continue;
				}
				for (Tile tile : column) {
					if (tile == null) {
						continue;
					}
					for (GameObject go : tile.getGameObjects()) {
						if (go != null && go.getId() == STORM_OBJECT_ID) {
							stormObjectCount++;
						}
					}
				}
			}
		}
		applyStormIntensity();
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event) {
		GameObject go = event.getGameObject();
		if (go == null || go.getId() != STORM_OBJECT_ID) {
			return;
		}
		stormObjectCount++;
		applyStormIntensity();
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event) {
		GameObject go = event.getGameObject();
		if (go == null || go.getId() != STORM_OBJECT_ID) {
			return;
		}
		stormObjectCount = Math.max(0, stormObjectCount - 1);
		applyStormIntensity();
	}

	private void resetStormState() {
		stormObjectCount = 0;
		lastStormState = null;
		clearRuntimeEffectors();
	}

	private void applyStormIntensity() {
		boolean storm = stormObjectCount > 0;

		if (lastStormState != null && lastStormState != storm && particleManager != null) {
			particleManager.clearParticleInstances();
		}
		lastStormState = storm;

		clearRuntimeEffectors();
		if (storm) {
			addRuntimeEffector(STORM_VORTEX_X, STORM_VORTEX_Y, STORM_VORTEX_PLANE, STORM);
			for (int[] wind : STORM_WIND_PLACEMENTS) {
				addRuntimeEffector(wind[0], wind[1], wind[2], STORM_WIND);
			}
		}

		applyModifierToMatching(storm ? STORM_MODIFIER : CALM_MODIFIER);
	}
}
