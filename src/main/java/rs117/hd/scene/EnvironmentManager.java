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

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DefaultSkyColor;
import rs117.hd.data.environments.Area;
import rs117.hd.data.environments.Environment;
import rs117.hd.utils.AABB;
import rs117.hd.utils.HDUtils;

import static rs117.hd.utils.HDUtils.clamp;
import static rs117.hd.utils.HDUtils.lerp;
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

	private WorldPoint previousPosition = new WorldPoint(0, 0, 0);

	private float[] startFogColor = new float[]{0,0,0};
	public float[] currentFogColor = new float[]{0,0,0};
	private float[] targetFogColor = new float[]{0,0,0};

	private float[] startWaterColor = new float[]{0,0,0};
	public float[] currentWaterColor = new float[]{0,0,0};
	private float[] targetWaterColor = new float[]{0,0,0};

	private int startFogDepth = 0;
	public int currentFogDepth = 0;
	private int targetFogDepth = 0;

	private float startAmbientStrength = 0f;
	public float currentAmbientStrength = 0f;
	private float targetAmbientStrength = 0f;

	private float[] startAmbientColor = new float[]{0,0,0};
	public float[] currentAmbientColor = new float[]{0,0,0};
	private float[] targetAmbientColor = new float[]{0,0,0};

	private float startDirectionalStrength = 0f;
	public float currentDirectionalStrength = 0f;
	private float targetDirectionalStrength = 0f;

	private float[] startUnderwaterCausticsColor = new float[]{0,0,0};
	public float[] currentUnderwaterCausticsColor = new float[]{0,0,0};
	private float[] targetUnderwaterCausticsColor = new float[]{0,0,0};

	private float startUnderwaterCausticsStrength = 1f;
	public float currentUnderwaterCausticsStrength = 1f;
	private float targetUnderwaterCausticsStrength = 1f;

	private float[] startDirectionalColor = new float[]{0,0,0};
	public float[] currentDirectionalColor = new float[]{0,0,0};
	private float[] targetDirectionalColor = new float[]{0,0,0};

	private float startUnderglowStrength = 0f;
	public float currentUnderglowStrength = 0f;
	private float targetUnderglowStrength = 0f;

	private float[] startUnderglowColor = new float[]{0,0,0};
	public float[] currentUnderglowColor = new float[]{0,0,0};
	private float[] targetUnderglowColor = new float[]{0,0,0};

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
	private boolean isOverworld = false;
	// some necessary data for reloading the scene while in POH to fix major performance loss
	private boolean isInHouse = false;
	private int previousPlane;

	public void reset() {
		currentEnvironment = Environment.NONE;
	}


	/**
	 * Updates variables used in transition effects
	 *
	 * @param sceneContext to possible environments from
	 */
	public void update(SceneContext sceneContext) {
		assert client.isClientThread();

		WorldPoint position = sceneContext.localToWorld(
			new LocalPoint(sceneContext.cameraFocalPoint[0], sceneContext.cameraFocalPoint[1]), client.getPlane());

		isOverworld = Area.OVERWORLD.containsPoint(position);

		// skip the transitional fade if the player has moved too far
		// since the previous frame. results in an instant transition when
		// teleporting, entering dungeons, etc.
		int tileChange = Math.max(
			Math.abs(position.getX() - previousPosition.getX()),
			Math.abs(position.getY() - previousPosition.getY())
		);
		previousPosition = position;

		// reload the scene if the player is in a house and their plane changed
		// this greatly improves the performance as it keeps the scene buffer up to date
		if (isInHouse) {
			int plane = client.getPlane();
			if (previousPlane != plane) {
				plugin.reloadSceneNextGameTick();
				previousPlane = plane;
			}
		}

		boolean skipTransition = tileChange >= SKIP_TRANSITION_DISTANCE;
		for (Environment environment : sceneContext.environments)
		{
			if (environment.getArea().containsPoint(position))
			{
				if (environment != currentEnvironment)
				{
					if (environment == Environment.PLAYER_OWNED_HOUSE || environment == Environment.PLAYER_OWNED_HOUSE_SNOWY) {
						// POH takes 1 game tick to enter, then 2 game ticks to load per floor
						plugin.reloadSceneIn(7);
						isInHouse = true;
					} else if (isInHouse) {
						// Avoid an unnecessary scene reload if the player has already left the POH
						plugin.abortSceneReload();
						isInHouse = false;
					}

					// Since the environment which actually gets used may differ from the environment
					// chosen based on position, update the plugin's area tracking here
					plugin.isInChambersOfXeric = environment == Environment.CHAMBERS_OF_XERIC;

					changeEnvironment(environment, skipTransition);
				}
				break;
			}
		}

		updateTargetSkyColor(); // Update every frame, since other plugins may control it

		// interpolate between start and target values
		long currentTime = System.currentTimeMillis();
		float t = clamp((currentTime - startTime) / (float) TRANSITION_DURATION, 0, 1);
		currentFogColor = lerp(startFogColor, targetFogColor, t);
		currentWaterColor = lerp(startWaterColor, targetWaterColor, t);
		currentFogDepth = (int) lerp(startFogDepth, targetFogDepth, t);
		currentAmbientStrength = lerp(startAmbientStrength, targetAmbientStrength, t);
		currentAmbientColor = lerp(startAmbientColor, targetAmbientColor, t);
		currentDirectionalStrength = lerp(startDirectionalStrength, targetDirectionalStrength, t);
		currentDirectionalColor = lerp(startDirectionalColor, targetDirectionalColor, t);
		currentUnderglowStrength = lerp(startUnderglowStrength, targetUnderglowStrength, t);
		currentUnderglowColor = lerp(startUnderglowColor, targetUnderglowColor, t);
		currentGroundFogStart = lerp(startGroundFogStart, targetGroundFogStart, t);
		currentGroundFogEnd = lerp(startGroundFogEnd, targetGroundFogEnd, t);
		currentGroundFogOpacity = lerp(startGroundFogOpacity, targetGroundFogOpacity, t);
		currentLightPitch = lerp(startLightPitch, targetLightPitch, t);
		currentLightYaw = lerp(startLightYaw, targetLightYaw, t);
		currentUnderwaterCausticsColor = lerp(startUnderwaterCausticsColor, targetUnderwaterCausticsColor, t);
		currentUnderwaterCausticsStrength = lerp(startUnderwaterCausticsStrength, targetUnderwaterCausticsStrength, t);

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
		if (skipTransition || currentEnvironment == Environment.NONE)
			startTime -= TRANSITION_DURATION;

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
		startLightPitch = currentLightPitch;
		startLightYaw = currentLightYaw;
		startUnderwaterCausticsColor = currentUnderwaterCausticsColor;
		startUnderwaterCausticsStrength = currentUnderwaterCausticsStrength;

		updateTargetSkyColor();

		targetFogDepth = newEnvironment.getFogDepth();
		if (useWinterTheme() && !newEnvironment.isCustomFogDepth()) {
			targetFogDepth = Environment.WINTER.getFogDepth();
		}

		Environment atmospheric = config.atmosphericLighting() ? newEnvironment : Environment.OVERWORLD;
		targetAmbientStrength = atmospheric.getAmbientStrength();
		targetAmbientColor = atmospheric.getAmbientColor();
		targetDirectionalStrength = atmospheric.getDirectionalStrength();
		targetDirectionalColor = atmospheric.getDirectionalColor();
		targetUnderglowStrength = atmospheric.getUnderglowStrength();
		targetUnderglowColor = atmospheric.getUnderglowColor();
		targetUnderwaterCausticsColor = atmospheric.getUnderwaterCausticsColor();
		targetUnderwaterCausticsStrength = atmospheric.getUnderwaterCausticsStrength();
		if (useWinterTheme()) {
			if (!atmospheric.isCustomAmbientStrength())
				targetAmbientStrength = Environment.WINTER.getAmbientStrength();
			if (!atmospheric.isCustomAmbientColor())
				targetAmbientColor = Environment.WINTER.getAmbientColor();
			if (!atmospheric.isCustomDirectionalStrength())
				targetDirectionalStrength = Environment.WINTER.getDirectionalStrength();
			if (!atmospheric.isCustomDirectionalColor())
				targetDirectionalColor = Environment.WINTER.getDirectionalColor();
		}

		targetLightPitch = newEnvironment.getLightPitch();
		targetLightYaw = newEnvironment.getLightYaw();
		targetGroundFogStart = newEnvironment.getGroundFogStart();
		targetGroundFogEnd = newEnvironment.getGroundFogEnd();
		targetGroundFogOpacity = newEnvironment.getGroundFogOpacity();
		lightningEnabled = newEnvironment.isLightningEnabled();
	}

	public void updateTargetSkyColor() {
		Environment env = useWinterTheme() ? Environment.WINTER : currentEnvironment;
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
		if(currentEnvironment.isCustomWaterColor())
			targetWaterColor = currentEnvironment.getWaterColor();
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

	public boolean isUnderwater() {
		return currentEnvironment.isUnderwater();
	}

	/**
	 * This should not be used from the scene loader thread
	 */
	private boolean useWinterTheme() {
		return plugin.configWinterTheme && isOverworld;
	}
}
