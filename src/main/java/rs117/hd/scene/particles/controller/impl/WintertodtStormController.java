/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package rs117.hd.scene.particles.controller.impl;

import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.*;
import net.runelite.client.eventbus.Subscribe;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.controller.AbstractParticleController;
import rs117.hd.scene.particles.controller.ParticleModifier;
import rs117.hd.scene.particles.effector.EffectorBuilder;
import rs117.hd.scene.particles.effector.EffectorRef;

@Singleton
public class WintertodtStormController extends AbstractParticleController {

	@Inject
	Client client;

	boolean stormActive;

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

	private Boolean lastStormState = false;

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

	@Subscribe
	public void onGameTick(GameTick event) {

		Widget widget = client.getWidget(InterfaceID.WintStatus.ENERGY_TITLE);
		boolean winterToadSpawned =
			widget != null &&
			widget.getText() != null &&
			widget.getText().contains("%");

		if (winterToadSpawned && !stormActive) {
			stormActive = true;
			applyStormIntensity();
		} else if (!winterToadSpawned && stormActive) {
			stormActive = false;
			applyStormIntensity();
		}
	}

	private void resetStormState() {
		lastStormState = null;
		clearRuntimeEffectors();
	}

	private void applyStormIntensity() {

		if (lastStormState != null && lastStormState != stormActive && particleManager != null) {
			particleManager.clearParticleInstances();
		}
		lastStormState = stormActive;

		clearRuntimeEffectors();
		if (stormActive) {
			addRuntimeEffector(STORM_VORTEX_X, STORM_VORTEX_Y, STORM_VORTEX_PLANE, STORM);
			for (int[] wind : STORM_WIND_PLACEMENTS) {
				addRuntimeEffector(wind[0], wind[1], wind[2], STORM_WIND);
			}
		}

		applyModifierToMatching(stormActive ? STORM_MODIFIER : CALM_MODIFIER);
	}
}
