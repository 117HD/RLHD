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

import java.util.ArrayList;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DefaultSkyColor;
import rs117.hd.data.environments.Area;
import rs117.hd.data.environments.Environment;
import rs117.hd.utils.AABB;
import rs117.hd.utils.HDUtils;

@Singleton
@Slf4j
public class EnvironmentManager
{
	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	private final Environment defaultEnvironment = Environment.OVERWORLD;
	private Environment currentEnvironment = defaultEnvironment;

	// transition time
	private static final int TRANSITION_DURATION = 3000;
	// distance in tiles to skip transition (e.g. entering cave, teleporting)
	// walking across a loading line causes a movement of 40-41 tiles
	private static final int SKIP_TRANSITION_DISTANCE = 41;

	// last environment change time
	private long startTime = 0;
	// transition complete time
	private long transitionCompleteTime = 0;
	// time of last frame; used for lightning
	long lastFrameTime = -1;

	private WorldPoint previousPosition = new WorldPoint(0, 0, 0);

	public static final float[] BLACK_COLOR = {0,0,0};

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

	public void startUp()
	{
		currentEnvironment = null;
		changeEnvironment(defaultEnvironment, true);
	}

	/**
	 * Updates variables used in transition effects
	 *
	 * @param sceneContext to possible environments from
	 * @param position     in world space that the camera is or will be looking at
	 */
	public void update(SceneContext sceneContext, WorldPoint position)
	{
		boolean skipTransition = false;

		// skip the transitional fade if the player has moved too far
		// since the previous frame. results in an instant transition when
		// teleporting, entering dungeons, etc.
		int tileChange = Math.max(
			Math.abs(position.getX() - previousPosition.getX()),
			Math.abs(position.getY() - previousPosition.getY())
		);

		previousPosition = position;

		if (tileChange >= SKIP_TRANSITION_DISTANCE)
		{
			skipTransition = true;
		}

		for (Environment environment : sceneContext.environments)
		{
			if (environment.getArea().containsPoint(position))
			{
				if (environment != currentEnvironment)
				{
					if (environment == Environment.PLAYER_OWNED_HOUSE || environment == Environment.PLAYER_OWNED_HOUSE_SNOWY) {
						plugin.setInHouse(true);

						// POH takes 1 game tick to enter, then 2 game ticks to load per floor
						plugin.reloadSceneIn(7);
					} else {
						plugin.setInHouse(false);
					}

					plugin.setInGauntlet(environment == Environment.THE_GAUNTLET || environment == Environment.THE_GAUNTLET_CORRUPTED);

					changeEnvironment(environment, skipTransition);
				}
				break;
			}
		}

		updateTargetSkyColor(); // Update every frame, since other plugins may control it

		// modify all environment values during transition
		long currentTime = System.currentTimeMillis();
		if (currentTime >= transitionCompleteTime)
		{
			currentFogColor = targetFogColor;
			currentWaterColor = targetWaterColor;
			currentFogDepth = targetFogDepth;
			currentAmbientStrength = targetAmbientStrength;
			currentAmbientColor = targetAmbientColor;
			currentDirectionalStrength = targetDirectionalStrength;
			currentDirectionalColor = targetDirectionalColor;
			currentUnderglowStrength = targetUnderglowStrength;
			currentUnderglowColor = targetUnderglowColor;
			currentGroundFogStart = targetGroundFogStart;
			currentGroundFogEnd = targetGroundFogEnd;
			currentGroundFogOpacity = targetGroundFogOpacity;
			currentLightPitch = targetLightPitch;
			currentLightYaw = targetLightYaw;
			currentUnderwaterCausticsColor = targetUnderwaterCausticsColor;
			currentUnderwaterCausticsStrength = targetUnderwaterCausticsStrength;
		}
		else
		{
			// interpolate between start and target values
			float t = (float)(currentTime - startTime) / (float) TRANSITION_DURATION;

			currentFogColor = HDUtils.lerpVectors(startFogColor, targetFogColor, t);
			currentWaterColor = HDUtils.lerpVectors(startWaterColor, targetWaterColor, t);
			currentFogDepth = (int) HDUtils.lerp(startFogDepth, targetFogDepth, t);
			currentAmbientStrength = HDUtils.lerp(startAmbientStrength, targetAmbientStrength, t);
			currentAmbientColor = HDUtils.lerpVectors(startAmbientColor, targetAmbientColor, t);
			currentDirectionalStrength = HDUtils.lerp(startDirectionalStrength, targetDirectionalStrength, t);
			currentDirectionalColor = HDUtils.lerpVectors(startDirectionalColor, targetDirectionalColor, t);
			currentUnderglowStrength = HDUtils.lerp(startUnderglowStrength, targetUnderglowStrength, t);
			currentUnderglowColor = HDUtils.lerpVectors(startUnderglowColor, targetUnderglowColor, t);
			currentGroundFogStart  = HDUtils.lerp(startGroundFogStart, targetGroundFogStart, t);
			currentGroundFogEnd  = HDUtils.lerp(startGroundFogEnd, targetGroundFogEnd, t);
			currentGroundFogOpacity  = HDUtils.lerp(startGroundFogOpacity, targetGroundFogOpacity, t);
			currentLightPitch = HDUtils.lerp(startLightPitch, targetLightPitch, t);
			currentLightYaw = HDUtils.lerp(startLightYaw, targetLightYaw, t);
			currentUnderwaterCausticsColor = HDUtils.lerpVectors(startUnderwaterCausticsColor, targetUnderwaterCausticsColor, t);
			currentUnderwaterCausticsStrength = HDUtils.lerp(startUnderwaterCausticsStrength, targetUnderwaterCausticsStrength, t);
		}

		updateLightning();

		// update some things for use next frame
		lastFrameTime = System.currentTimeMillis();
	}

	/**
	 * Updates variables used in transition effects
	 *
	 * @param newEnvironment the new environment to transition to
	 * @param skipTransition whether the transition should be done instantly
	 */
	private void changeEnvironment(Environment newEnvironment, boolean skipTransition)
	{
		if (currentEnvironment == newEnvironment)
			return;

		startTime = System.currentTimeMillis();
		transitionCompleteTime = startTime + (skipTransition ? 0 : TRANSITION_DURATION);

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

		Environment atmospheric = config.atmosphericLighting() ? newEnvironment : defaultEnvironment;
		targetAmbientStrength = atmospheric.getAmbientStrength();
		targetAmbientColor = atmospheric.getAmbientColor();
		targetDirectionalStrength = atmospheric.getDirectionalStrength();
		targetDirectionalColor = atmospheric.getDirectionalColor();
		targetUnderglowStrength = atmospheric.getUnderglowStrength();
		targetUnderglowColor = atmospheric.getUnderglowColor();
		targetLightPitch = atmospheric.getLightPitch();
		targetLightYaw = atmospheric.getLightYaw();
		if (useWinterTheme())
		{
			if (!atmospheric.isCustomAmbientStrength())
			{
				targetAmbientStrength = Environment.WINTER.getAmbientStrength();
			}
			if (!atmospheric.isCustomAmbientColor())
			{
				targetAmbientColor = Environment.WINTER.getAmbientColor();
			}
			if (!atmospheric.isCustomDirectionalStrength())
			{
				targetDirectionalStrength = Environment.WINTER.getDirectionalStrength();
			}
			if (!atmospheric.isCustomDirectionalColor())
			{
				targetDirectionalColor = Environment.WINTER.getDirectionalColor();
			}
		}

		targetGroundFogStart = newEnvironment.getGroundFogStart();
		targetGroundFogEnd = newEnvironment.getGroundFogEnd();
		targetGroundFogOpacity = newEnvironment.getGroundFogOpacity();
		targetUnderwaterCausticsColor = newEnvironment.getUnderwaterCausticsColor();
		targetUnderwaterCausticsStrength = newEnvironment.getUnderwaterCausticsStrength();

		lightningEnabled = newEnvironment.isLightningEnabled();
	}

	public void updateTargetSkyColor()
	{
		Environment env = useWinterTheme() ? Environment.WINTER : currentEnvironment;
		if (!env.isCustomFogColor() || env.isAllowSkyOverride() && config.overrideSky())
		{
			DefaultSkyColor sky = config.defaultSkyColor();
			targetFogColor = sky.getRgb(client);
			if (sky == DefaultSkyColor.OSRS)
			{
				sky = DefaultSkyColor.DEFAULT;
			}
			targetWaterColor = sky.getRgb(client);
		}
		else
		{
			targetFogColor = targetWaterColor = env.getFogColor();
		}


		//override with decoupled water/sky color if present
		if(currentEnvironment.isCustomWaterColor())
		{
			targetWaterColor = currentEnvironment.getWaterColor();
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

		log.debug("Adding environments for scene {}", sceneContext.bounds);

		sceneContext.environments = new ArrayList<>();
		for (Environment environment: Environment.values())
		{
			for (AABB aabb : environment.getArea().getAabbs())
			{
				if (sceneContext.bounds.intersects(aabb))
				{
					log.debug("Added environment: {}", environment);
					sceneContext.environments.add(environment);
					break;
				}
			}
		}

		WorldPoint sceneCenter = HDUtils.getSceneCenter(sceneContext.scene, client.getPlane());
		isOverworld = Area.OVERWORLD.containsPoint(sceneCenter);
		update(sceneContext, sceneCenter);
	}



	/* lightning */
	private static final float[] LIGHTNING_COLOR = new float[]{.25f, .25f, .25f};
	private static final float NEW_LIGHTNING_BRIGHTNESS = 7f;
	private static final float LIGHTNING_FADE_SPEED = 80f; // brightness units per second
	private static final int MIN_LIGHTNING_INTERVAL = 5500;
	private static final int MAX_LIGHTNING_INTERVAL = 17000;
	private static final float QUICK_LIGHTNING_CHANCE = 2f;
	private static final int MIN_QUICK_LIGHTNING_INTERVAL = 40;
	private static final int MAX_QUICK_LIGHTNING_INTERVAL = 150;

	@Getter
	private float lightningBrightness = 0f;
	private double nextLightningTime = -1;

	/**
	 * Updates lightning variables and sets water reflection and sky
	 * colors during lightning flashes.
	 */
	void updateLightning()
	{
		if (lightningBrightness > 0)
		{
			int frameTime = (int)(System.currentTimeMillis() - lastFrameTime);
			float brightnessChange = (frameTime / 1000f) * LIGHTNING_FADE_SPEED;
			lightningBrightness = Math.max(lightningBrightness - brightnessChange, 0);
		}

		if (nextLightningTime == -1)
		{
			generateNextLightningTime();
			return;
		}
		if (System.currentTimeMillis() > nextLightningTime)
		{
			lightningBrightness = NEW_LIGHTNING_BRIGHTNESS;

			generateNextLightningTime();
		}

		if (lightningEnabled && config.flashingEffects())
		{
			float t = HDUtils.clamp(lightningBrightness, 0, 1);
			currentFogColor = HDUtils.lerpVectors(currentFogColor, LIGHTNING_COLOR, t);
			currentWaterColor = HDUtils.lerpVectors(currentWaterColor, LIGHTNING_COLOR, t);
		}
		else
		{
			lightningBrightness = 0f;
		}
	}

	/**
	 * Determines when the next lighting strike will occur.
	 * Produces a short interval for a quick successive strike
	 * or a longer interval at the end of a cluster.
	 */
	void generateNextLightningTime()
	{
		int lightningInterval = (int)(MIN_LIGHTNING_INTERVAL + ((MAX_LIGHTNING_INTERVAL - MIN_LIGHTNING_INTERVAL) * Math.random()));
		int quickLightningInterval = (int)(MIN_QUICK_LIGHTNING_INTERVAL + ((MAX_QUICK_LIGHTNING_INTERVAL - MIN_QUICK_LIGHTNING_INTERVAL) * Math.random()));
		if (Math.random() <= 1f / QUICK_LIGHTNING_CHANCE)
		{
			// chain together lighting strikes in quick succession
			nextLightningTime = System.currentTimeMillis() + quickLightningInterval;
		}
		else
		{
			// cool-down period before a new lightning cluster
			nextLightningTime = System.currentTimeMillis() + lightningInterval;
		}
	}

	/**
	 * Returns the current fog color if logged in.
	 * Else, returns solid black.
	 *
	 * @return 3-component RGB color array
	 */
	public float[] getFogColor()
	{
		return Arrays.copyOf(client.getGameState().getState() >= GameState.LOADING.getState() ?
			currentFogColor : BLACK_COLOR, 3);
	}

	public boolean isUnderwater()
	{
		return currentEnvironment.isUnderwater();
	}

	private boolean useWinterTheme() {
		return plugin.configWinterTheme && isOverworld;
	}
}
