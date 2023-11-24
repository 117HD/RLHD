/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.scene;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DefaultSkyColor;
import rs117.hd.data.environments.Environment;
import rs117.hd.utils.AABB;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.HDUtils.clamp;
import static rs117.hd.utils.HDUtils.hermite;
import static rs117.hd.utils.HDUtils.lerp;
import static rs117.hd.utils.HDUtils.mod;
import static rs117.hd.utils.HDUtils.rand;

@Singleton
@Slf4j
public class EnvironmentManager {
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Nonnull
	private Environment currentEnvironment = Environment.NONE;

	// transition time
	private static final int TRANSITION_DURATION = 3000;
	// distance in tiles to skip transition (e.g. entering cave, teleporting)
	// walking across a loading line causes a movement of 40-41 tiles
	private static final int SKIP_TRANSITION_DISTANCE = 41;

	// last environment change time
	private long startTime = 0;
	// time of last frame; used for lightning
	long lastFrameTime = -1;

	private int[] previousPosition = new int[3];

	private float[] startFogColor = new float[] { 0, 0, 0 };
	public float[] currentFogColor = new float[] { 0, 0, 0 };
	private float[] targetFogColor = new float[] { 0, 0, 0 };

	private float[] startWaterColor = new float[] { 0, 0, 0 };
	public float[] currentWaterColor = new float[] { 0, 0, 0 };
	private float[] targetWaterColor = new float[] { 0, 0, 0 };

	private float startFogDepth = 0;
	public float currentFogDepth = 0;
	private float targetFogDepth = 0;

	private float startAmbientStrength = 0f;
	public float currentAmbientStrength = 0f;
	private float targetAmbientStrength = 0f;

	private float[] startAmbientColor = new float[] { 0, 0, 0 };
	public float[] currentAmbientColor = new float[] { 0, 0, 0 };
	private float[] targetAmbientColor = new float[] { 0, 0, 0 };

	private float startDirectionalStrength = 0f;
	public float currentDirectionalStrength = 0f;
	private float targetDirectionalStrength = 0f;

	private float[] startUnderwaterCausticsColor = new float[] { 0, 0, 0 };
	public float[] currentUnderwaterCausticsColor = new float[] { 0, 0, 0 };
	private float[] targetUnderwaterCausticsColor = new float[] { 0, 0, 0 };

	private float startUnderwaterCausticsStrength = 1f;
	public float currentUnderwaterCausticsStrength = 1f;
	private float targetUnderwaterCausticsStrength = 1f;

	private float[] startDirectionalColor = new float[] { 0, 0, 0 };
	public float[] currentDirectionalColor = new float[] { 0, 0, 0 };
	private float[] targetDirectionalColor = new float[] { 0, 0, 0 };

	private float startUnderglowStrength = 0f;
	public float currentUnderglowStrength = 0f;
	private float targetUnderglowStrength = 0f;

	private float[] startUnderglowColor = new float[] { 0, 0, 0 };
	public float[] currentUnderglowColor = new float[] { 0, 0, 0 };
	private float[] targetUnderglowColor = new float[] { 0, 0, 0 };

	private float startGroundFogStart = 0f;
	public float currentGroundFogStart = 0f;
	private float targetGroundFogStart = 0f;

	private float startGroundFogEnd = 0f;
	public float currentGroundFogEnd = 0f;
	private float targetGroundFogEnd = 0f;

	private float startGroundFogOpacity = 0f;
	public float currentGroundFogOpacity = 0f;
	private float targetGroundFogOpacity = 0f;

	private float startLightPitch = 0f;
	public float currentLightPitch = 0f;
	private float targetLightPitch = 0f;

	private float startLightYaw = 0f;
	public float currentLightYaw = 0f;
	private float targetLightYaw = 0f;

	private boolean lightningEnabled = false;
	private boolean forceNextTransition = false;

	public void reset() {
		currentEnvironment = Environment.NONE;
		forceNextTransition = false;
	}

	public void triggerTransition() {
		reset();
		forceNextTransition = true;
	}


	/**
	 * Updates variables used in transition effects
	 *
	 * @param sceneContext to possible environments from
	 */
	public void update(SceneContext sceneContext) {
		assert client.isClientThread();

		int[] focalPoint = sceneContext.localToWorld(
			plugin.cameraFocalPoint[0],
			plugin.cameraFocalPoint[1],
			client.getPlane()
		);

		// skip the transitional fade if the player has moved too far
		// since the previous frame. results in an instant transition when
		// teleporting, entering dungeons, etc.
		int tileChange = Math.max(
			Math.abs(focalPoint[0] - previousPosition[0]),
			Math.abs(focalPoint[1] - previousPosition[1])
		);
		previousPosition = focalPoint;

		boolean skipTransition = tileChange >= SKIP_TRANSITION_DISTANCE;
		for (Environment environment : sceneContext.environments)
		{
			if (environment.getArea().containsPoint(focalPoint))
			{
				if (environment != currentEnvironment)
				{
					changeEnvironment(environment, skipTransition);
				}
				break;
			}
		}

		updateTargetSkyColor(); // Update every frame, since other plugins may control it

		// interpolate between start and target values
		long currentTime = System.currentTimeMillis();
		float t = clamp((currentTime - startTime) / (float) TRANSITION_DURATION, 0, 1);
		currentFogColor = hermite(startFogColor, targetFogColor, t);
		currentWaterColor = hermite(startWaterColor, targetWaterColor, t);
		currentFogDepth = hermite(startFogDepth, targetFogDepth, t);
		currentAmbientStrength = hermite(startAmbientStrength, targetAmbientStrength, t);
		currentAmbientColor = hermite(startAmbientColor, targetAmbientColor, t);
		currentDirectionalStrength = hermite(startDirectionalStrength, targetDirectionalStrength, t);
		currentDirectionalColor = hermite(startDirectionalColor, targetDirectionalColor, t);
		currentUnderglowStrength = hermite(startUnderglowStrength, targetUnderglowStrength, t);
		currentUnderglowColor = hermite(startUnderglowColor, targetUnderglowColor, t);
		currentGroundFogStart = hermite(startGroundFogStart, targetGroundFogStart, t);
		currentGroundFogEnd = hermite(startGroundFogEnd, targetGroundFogEnd, t);
		currentGroundFogOpacity = hermite(startGroundFogOpacity, targetGroundFogOpacity, t);
		currentLightPitch = hermite(startLightPitch, targetLightPitch, t);
		currentLightYaw = hermite(startLightYaw, targetLightYaw, t);
		currentUnderwaterCausticsColor = hermite(startUnderwaterCausticsColor, targetUnderwaterCausticsColor, t);
		currentUnderwaterCausticsStrength = hermite(startUnderwaterCausticsStrength, targetUnderwaterCausticsStrength, t);

		updateLightning();

		// update some things for use next frame
		lastFrameTime = currentTime;
	}

	/**
	 * Updates variables used in transition effects
	 *
	 * @param newEnvironment the new environment to transition to
	 * @param skipTransition whether the transition should be done instantly
	 */
	private void changeEnvironment(Environment newEnvironment, boolean skipTransition) {
		if (currentEnvironment == newEnvironment)
			return;

		startTime = System.currentTimeMillis();
		if (forceNextTransition) {
			forceNextTransition = false;
		} else if (skipTransition || currentEnvironment == Environment.NONE) {
			startTime -= TRANSITION_DURATION;
		}

		log.debug("changing environment from {} to {} (instant: {})", currentEnvironment, newEnvironment, skipTransition);
		currentEnvironment = newEnvironment;

		// set previous variables to current ones
		startFogColor = currentFogColor;
		startWaterColor = currentWaterColor;
		startFogDepth = currentFogDepth;
		startAmbientStrength = currentAmbientStrength;
		startAmbientColor = currentAmbientColor;
		startDirectionalStrength = currentDirectionalStrength;
		startDirectionalColor = currentDirectionalColor;
		startUnderglowStrength = currentUnderglowStrength;
		startUnderglowColor = currentUnderglowColor;
		startGroundFogStart = currentGroundFogStart;
		startGroundFogEnd = currentGroundFogEnd;
		startGroundFogOpacity = currentGroundFogOpacity;
		startUnderwaterCausticsColor = currentUnderwaterCausticsColor;
		startUnderwaterCausticsStrength = currentUnderwaterCausticsStrength;
		startLightPitch = mod(currentLightPitch, 360);
		startLightYaw = mod(currentLightYaw, 360);

		updateTargetSkyColor();

		var overworldEnv = getOverworldEnvironment();
		var env = getCurrentEnvironment();
		targetFogDepth = env.getFogDepth();
		targetGroundFogStart = env.getGroundFogStart();
		targetGroundFogEnd = env.getGroundFogEnd();
		targetGroundFogOpacity = env.getGroundFogOpacity();
		lightningEnabled = env.isLightningEnabled();

		if (env.isCustomLightDirection()) {
			targetLightPitch = env.getLightPitch();
			targetLightYaw = env.getLightYaw();
		} else {
			targetLightPitch = overworldEnv.getLightPitch();
			targetLightYaw = overworldEnv.getLightYaw();
		}

		if (!config.atmosphericLighting())
			env = overworldEnv;
		targetAmbientStrength = env.getAmbientStrength();
		targetAmbientColor = env.getAmbientColor();
		targetDirectionalStrength = env.getDirectionalStrength();
		targetDirectionalColor = env.getDirectionalColor();
		targetUnderglowStrength = env.getUnderglowStrength();
		targetUnderglowColor = env.getUnderglowColor();
		targetUnderwaterCausticsColor = env.getUnderwaterCausticsColor();
		targetUnderwaterCausticsStrength = env.getUnderwaterCausticsStrength();

		// Prevent transitions from taking the long way around
		targetLightPitch = mod(targetLightPitch, 360);
		targetLightYaw = mod(targetLightYaw, 360);
		float diff = startLightYaw - targetLightYaw;
		if (Math.abs(diff) > 180)
			targetLightYaw += 360 * Math.signum(diff);
		diff = startLightPitch - targetLightPitch;
		if (Math.abs(diff) > 180)
			targetLightPitch += 360 * Math.signum(diff);
	}

	public void updateTargetSkyColor() {
		Environment env = getCurrentEnvironment();

		if (!env.isCustomFogColor() || env.isAllowSkyOverride() && config.overrideSky()) {
			DefaultSkyColor sky = config.defaultSkyColor();
			targetFogColor = sky.getRgb(client);
			if (sky == DefaultSkyColor.OSRS)
				sky = DefaultSkyColor.DEFAULT;
			targetWaterColor = sky.getRgb(client);
		} else {
			targetFogColor = targetWaterColor = env.getFogColor();
		}

		// Override with decoupled water/sky color if present
		if (env.isCustomWaterColor()) {
			targetWaterColor = env.getWaterColor();
		} else if (config.decoupleSkyAndWaterColor()) {
			targetWaterColor = DefaultSkyColor.DEFAULT.getRgb(client);
		}
	}

	/**
	 * Figures out which Areas exist in the current scene and
	 * adds them to lists for easy access.
	 */
	public void loadSceneEnvironments(SceneContext sceneContext)
	{
		// loop through all Areas, check Rects of each Area. if any
		// coordinates overlap scene coordinates, add them to a list.
		// then loop through all Environments, checking to see if any
		// of their Areas match any of the ones in the current scene.
		// if so, add them to a list.

		log.debug("Adding environments for scene with regions: {}", sceneContext.regionIds);

		AABB[] regions = sceneContext.regionIds.stream()
			.map(AABB::new)
			.toArray(AABB[]::new);

		sceneContext.environments.clear();
		outer:
		for (Environment environment : Environment.values())
		{
			for (AABB region : regions)
			{
				for (AABB aabb : environment.getArea().getAabbs())
				{
					if (region.intersects(aabb))
					{
						log.debug("Added environment: {}", environment);
						sceneContext.environments.add(environment);
						continue outer;
					}
				}
			}
		}
	}

	/* lightning */
	private static final float[] LIGHTNING_COLOR = new float[]{.25f, .25f, .25f};
	private static final float NEW_LIGHTNING_BRIGHTNESS = 7f;
	private static final float LIGHTNING_FADE_SPEED = 80f; // brightness units per second
	private static final int MIN_LIGHTNING_INTERVAL = 5500;
	private static final int MAX_LIGHTNING_INTERVAL = 17000;
	private static final float QUICK_LIGHTNING_CHANCE = .5f;
	private static final int MIN_QUICK_LIGHTNING_INTERVAL = 40;
	private static final int MAX_QUICK_LIGHTNING_INTERVAL = 150;

	@Getter
	private float lightningBrightness = 0f;
	private double nextLightningTime = -1;

	/**
	 * Updates lightning variables and sets water reflection and sky
	 * colors during lightning flashes.
	 */
	void updateLightning() {
		if (lightningBrightness > 0) {
			int frameTime = (int) (System.currentTimeMillis() - lastFrameTime);
			float brightnessChange = (frameTime / 1000f) * LIGHTNING_FADE_SPEED;
			lightningBrightness = Math.max(lightningBrightness - brightnessChange, 0);
		}

		if (nextLightningTime == -1) {
			generateNextLightningTime();
			return;
		}
		if (System.currentTimeMillis() > nextLightningTime) {
			lightningBrightness = NEW_LIGHTNING_BRIGHTNESS;
			generateNextLightningTime();
		}

		if (lightningEnabled && config.flashingEffects()) {
			float t = HDUtils.clamp(lightningBrightness, 0, 1);
			currentFogColor = lerp(currentFogColor, LIGHTNING_COLOR, t);
			currentWaterColor = lerp(currentWaterColor, LIGHTNING_COLOR, t);
		} else {
			lightningBrightness = 0f;
		}
	}

	/**
	 * Determines when the next lighting strike will occur.
	 * Produces a short interval for a quick successive strike
	 * or a longer interval at the end of a cluster.
	 */
	void generateNextLightningTime() {
		nextLightningTime = System.currentTimeMillis();
		if (Math.random() <= QUICK_LIGHTNING_CHANCE) {
			// chain together lighting strikes in quick succession
			nextLightningTime += lerp(MIN_QUICK_LIGHTNING_INTERVAL, MAX_QUICK_LIGHTNING_INTERVAL, rand.nextFloat());
		} else {
			// cool-down period before a new lightning cluster
			nextLightningTime += lerp(MIN_LIGHTNING_INTERVAL, MAX_LIGHTNING_INTERVAL, rand.nextFloat());
		}
	}

	private Environment getCurrentEnvironment() {
		if (currentEnvironment == Environment.OVERWORLD)
			return getOverworldEnvironment();
		return currentEnvironment;
	}

	private Environment getOverworldEnvironment() {
		switch (plugin.configSeasonalTheme) {
			case AUTUMN:
				return Environment.AUTUMN;
			case WINTER:
				return Environment.WINTER;
			default:
				return Environment.OVERWORLD;
		}
	}

	public boolean isUnderwater() {
		return currentEnvironment.isUnderwater();
	}
}
