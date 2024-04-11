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

import java.io.IOException;
import java.util.HashMap;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DefaultSkyColor;
import rs117.hd.scene.environments.Environment;
import rs117.hd.utils.AABB;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.HDUtils.PI;
import static rs117.hd.utils.HDUtils.TWO_PI;
import static rs117.hd.utils.HDUtils.clamp;
import static rs117.hd.utils.HDUtils.hermite;
import static rs117.hd.utils.HDUtils.lerp;
import static rs117.hd.utils.HDUtils.mod;
import static rs117.hd.utils.HDUtils.rand;
import static rs117.hd.utils.ResourcePath.path;

@Singleton
@Slf4j
public class EnvironmentManager {
	private static final ResourcePath ENVIRONMENTS_PATH = Props.getPathOrDefault(
		"rlhd.environments-path",
		() -> path(EnvironmentManager.class, "environments.json")
	);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	private static final float TRANSITION_DURATION = 3; // seconds
	// distance in tiles to skip transition (e.g. entering cave, teleporting)
	// walking across a loading line causes a movement of 40-41 tiles
	private static final int SKIP_TRANSITION_DISTANCE = 41;

	// when the current transition began, relative to plugin startup
	private boolean transitionComplete = true;
	private float transitionStartTime = 0;
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

	private final float[] startSunAngles = { 0, 0 };
	public final float[] currentSunAngles = { 0, 0 };
	private final float[] targetSunAngles = { 0, 0 };

	private boolean lightningEnabled = false;
	private boolean forceNextTransition = false;

	private rs117.hd.scene.environments.Environment[] environments;
	private FileWatcher.UnregisterCallback fileWatcher;

	@Nonnull
	private Environment currentEnvironment = Environment.NONE;

	public void startUp() {
		fileWatcher = ENVIRONMENTS_PATH.watch((path, first) -> {
			try {
				environments = path.loadJson(plugin.getGson(), rs117.hd.scene.environments.Environment[].class);
				if (environments == null)
					throw new IOException("Empty or invalid: " + path);
				log.debug("Loaded {} environments", environments.length);

				HashMap<String, Environment> map = new HashMap<>();
				for (var env : environments)
					if (env.key != null)
						map.put(env.key, env);

				Environment.OVERWORLD = map.getOrDefault("OVERWORLD", Environment.DEFAULT);
				Environment.AUTUMN = map.getOrDefault("AUTUMN", Environment.DEFAULT);
				Environment.WINTER = map.getOrDefault("WINTER", Environment.DEFAULT);

				for (var env : environments)
					env.normalize();

				clientThread.invoke(() -> {
					// Force instant transition during development
					if (!first)
						reset();

					if (client.getGameState().getState() >= GameState.LOGGED_IN.getState() && plugin.getSceneContext() != null)
						loadSceneEnvironments(plugin.getSceneContext());
				});
			} catch (IOException ex) {
				log.error("Failed to load environments:", ex);
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		environments = null;
		reset();
	}

	public void reset() {
		currentEnvironment = Environment.NONE;
		forceNextTransition = false;
	}

	public void triggerTransition() {
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
		for (var environment : sceneContext.environments) {
			if (environment.area.containsPoint(focalPoint)) {
				changeEnvironment(environment, skipTransition);
				break;
			}
		}

		updateTargetSkyColor(); // Update every frame, since other plugins may control it

		if (transitionComplete) {
			// Always write fog and water color, since they're affected by lightning
			currentFogColor = targetFogColor;
			currentWaterColor = targetWaterColor;
		} else {
			// interpolate between start and target values
			float t = clamp((plugin.elapsedTime - transitionStartTime) / TRANSITION_DURATION, 0, 1);
			if (t >= 1)
				transitionComplete = true;
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
			for (int i = 0; i < 2; i++)
				currentSunAngles[i] = hermite(startSunAngles[i], targetSunAngles[i], t);
			currentUnderwaterCausticsColor = hermite(startUnderwaterCausticsColor, targetUnderwaterCausticsColor, t);
			currentUnderwaterCausticsStrength = hermite(startUnderwaterCausticsStrength, targetUnderwaterCausticsStrength, t);
		}

		updateLightning();
	}

	/**
	 * Updates variables used in transition effects
	 *
	 * @param newEnvironment the new environment to transition to
	 * @param skipTransition whether the transition should be done instantly
	 */
	private void changeEnvironment(Environment newEnvironment, boolean skipTransition) {
		// Skip changing the environment unless the transition is forced, since reapplying
		// the overworld environment is required when switching between seasonal themes
		if (currentEnvironment == newEnvironment && !forceNextTransition)
			return;

		if (currentEnvironment == Environment.NONE) {
			skipTransition = true;
		} else if (forceNextTransition) {
			forceNextTransition = false;
			skipTransition = false;
		}

		if (currentEnvironment.instantTransition || newEnvironment.instantTransition)
			skipTransition = true;

		log.debug("changing environment from {} to {} (instant: {})", currentEnvironment, newEnvironment, skipTransition);
		currentEnvironment = newEnvironment;
		transitionComplete = false;
		transitionStartTime = plugin.elapsedTime - (skipTransition ? TRANSITION_DURATION : 0);

		// Start transitioning from the current values
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
		for (int i = 0; i < 2; i++)
			startSunAngles[i] = mod(currentSunAngles[i], TWO_PI);

		updateTargetSkyColor();

		var env = getCurrentEnvironment();
		targetFogDepth = env.fogDepth;
		targetGroundFogStart = env.groundFogStart;
		targetGroundFogEnd = env.groundFogEnd;
		targetGroundFogOpacity = env.groundFogOpacity;
		lightningEnabled = env.lightningEffects;

		var overworldEnv = getOverworldEnvironment();
		float[] sunAngles = env.sunAngles;
		if (sunAngles == null)
			sunAngles = overworldEnv.sunAngles;
		System.arraycopy(sunAngles, 0, targetSunAngles, 0, 2);

		if (!config.atmosphericLighting() && !env.force)
			env = overworldEnv;
		targetAmbientStrength = env.ambientStrength;
		targetAmbientColor = env.ambientColor;
		targetDirectionalStrength = env.directionalStrength;
		targetDirectionalColor = env.directionalColor;
		targetUnderglowStrength = env.underglowStrength;
		targetUnderglowColor = env.underglowColor;
		targetUnderwaterCausticsColor = env.waterCausticsColor;
		targetUnderwaterCausticsStrength = env.waterCausticsStrength;

		// Prevent transitions from taking the long way around
		for (int i = 0; i < 2; i++) {
			float diff = startSunAngles[i] - targetSunAngles[i];
			if (Math.abs(diff) > PI)
				targetSunAngles[i] += TWO_PI * Math.signum(diff);
		}
	}

	public void updateTargetSkyColor() {
		Environment env = getCurrentEnvironment();

		if (env.fogColor == null || env.allowSkyOverride && config.overrideSky()) {
			DefaultSkyColor sky = config.defaultSkyColor();
			targetFogColor = sky.getRgb(client);
			if (sky == DefaultSkyColor.OSRS)
				sky = DefaultSkyColor.DEFAULT;
			targetWaterColor = sky.getRgb(client);
		} else {
			targetFogColor = targetWaterColor = env.fogColor;
		}

		// Override with decoupled water/sky color if present
		if (env.waterColor != null) {
			targetWaterColor = env.waterColor;
		} else if (config.decoupleSkyAndWaterColor()) {
			targetWaterColor = DefaultSkyColor.DEFAULT.getRgb(client);
		}
	}

	/**
	 * Figures out which Areas exist in the current scene and
	 * adds them to lists for easy access.
	 */
	public void loadSceneEnvironments(SceneContext sceneContext) {
		log.debug("Adding environments for scene with regions: {}", sceneContext.regionIds);

		AABB[] regions = sceneContext.regionIds.stream()
			.map(AABB::new)
			.toArray(AABB[]::new);

		sceneContext.environments.clear();
		outer:
		for (var environment : environments) {
			for (AABB region : regions) {
				for (AABB aabb : environment.area.getAabbs()) {
					if (region.intersects(aabb)) {
						log.debug("Added environment: {}", environment);
						sceneContext.environments.add(environment);
						continue outer;
					}
				}
			}
		}

		// Fall back to the default environment
		sceneContext.environments.add(Environment.DEFAULT);
	}

	/* lightning */
	private static final float[] LIGHTNING_COLOR = new float[]{.25f, .25f, .25f};
	private static final float NEW_LIGHTNING_BRIGHTNESS = 7f;
	private static final float LIGHTNING_FADE_SPEED = 80f; // brightness units per second
	private static final float MIN_LIGHTNING_INTERVAL = 5.5f;
	private static final float MAX_LIGHTNING_INTERVAL = 17f;
	private static final float QUICK_LIGHTNING_CHANCE = .5f;
	private static final float MIN_QUICK_LIGHTNING_INTERVAL = .04f;
	private static final float MAX_QUICK_LIGHTNING_INTERVAL = .15f;

	@Getter
	private float lightningBrightness = 0f;
	private double nextLightningTime = -1;

	/**
	 * Updates lightning variables and sets water reflection and sky
	 * colors during lightning flashes.
	 */
	void updateLightning() {
		if (lightningBrightness > 0) {
			float brightnessChange = plugin.deltaTime * LIGHTNING_FADE_SPEED;
			lightningBrightness = Math.max(lightningBrightness - brightnessChange, 0);
		}

		if (nextLightningTime == -1) {
			generateNextLightningTime();
			return;
		}
		if (plugin.elapsedTime > nextLightningTime) {
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
		nextLightningTime = plugin.elapsedTime;
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
		return currentEnvironment.isUnderwater;
	}
}
