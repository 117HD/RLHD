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
import java.util.Objects;
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
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

@Slf4j
@Singleton
public class EnvironmentManager {
	private static final ResourcePath ENVIRONMENTS_PATH = Props
		.getFile("rlhd.environments-path", () -> path(EnvironmentManager.class, "environments.json"));

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
	private double transitionStartTime = 0;
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

	private float startWindAngle = 0f;
	public float currentWindAngle = 0f;
	private float targetWindAngle = 0f;

	private float startWindSpeed = 0f;
	public float currentWindSpeed = 0f;
	private float targetWindSpeed = 0f;

	private float startWindStrength = 0f;
	public float currentWindStrength = 0f;
	private float targetWindStrength = 0f;

	private float startWindCeiling = 0f;
	public float currentWindCeiling = 0f;
	private float targetWindCeiling = 0f;

	private boolean lightningEnabled = false;
	private boolean forceNextTransition = false;

	private Environment[] environments;
	private FileWatcher.UnregisterCallback fileWatcher;

	@Nonnull
	private Environment currentEnvironment = Environment.NONE;

	public void startUp() {
		fileWatcher = ENVIRONMENTS_PATH.watch((path, first) -> {
			try {
				environments = path.loadJson(plugin.getGson(), Environment[].class);
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
		int tileChange = (int) max(abs(subtract(vec(focalPoint), vec(previousPosition))));
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
			float t = smoothstep(0, 1, (float) (plugin.elapsedTime - transitionStartTime) / TRANSITION_DURATION);
			if (t >= 1)
				transitionComplete = true;
			currentFogColor = mix(startFogColor, targetFogColor, t);
			currentWaterColor = mix(startWaterColor, targetWaterColor, t);
			currentFogDepth = mix(startFogDepth, targetFogDepth, t);
			currentAmbientStrength = mix(startAmbientStrength, targetAmbientStrength, t);
			currentAmbientColor = mix(startAmbientColor, targetAmbientColor, t);
			currentDirectionalStrength = mix(startDirectionalStrength, targetDirectionalStrength, t);
			currentDirectionalColor = mix(startDirectionalColor, targetDirectionalColor, t);
			currentUnderglowStrength = mix(startUnderglowStrength, targetUnderglowStrength, t);
			currentUnderglowColor = mix(startUnderglowColor, targetUnderglowColor, t);
			currentGroundFogStart = mix(startGroundFogStart, targetGroundFogStart, t);
			currentGroundFogEnd = mix(startGroundFogEnd, targetGroundFogEnd, t);
			currentGroundFogOpacity = mix(startGroundFogOpacity, targetGroundFogOpacity, t);
			for (int i = 0; i < 2; i++)
				currentSunAngles[i] = mix(startSunAngles[i], targetSunAngles[i], t);
			currentUnderwaterCausticsColor = mix(startUnderwaterCausticsColor, targetUnderwaterCausticsColor, t);
			currentUnderwaterCausticsStrength = mix(startUnderwaterCausticsStrength, targetUnderwaterCausticsStrength, t);
			currentWindAngle = mix(startWindAngle, targetWindAngle, t);
			currentWindSpeed = mix(startWindSpeed, targetWindSpeed, t);
			currentWindStrength = mix(startWindStrength, targetWindStrength, t);
			currentWindCeiling = mix(startWindCeiling, targetWindCeiling, t);
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
		startWindAngle = currentWindAngle;
		startWindSpeed = currentWindSpeed;
		startWindStrength = currentWindStrength;
		startWindCeiling = currentWindCeiling;
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
			sunAngles = Objects.requireNonNullElse(overworldEnv.sunAngles, Environment.DEFAULT_SUN_ANGLES);
		copyTo(targetSunAngles, sunAngles);

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
		targetWindAngle = env.windAngle;
		targetWindSpeed = env.windSpeed;
		targetWindStrength = env.windStrength;
		targetWindCeiling = env.windCeiling;

		// Prevent transitions from taking the long way around
		for (int i = 0; i < 2; i++) {
			float diff = startSunAngles[i] - targetSunAngles[i];
			if (abs(diff) > PI)
				targetSunAngles[i] += TWO_PI * sign(diff);
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
		log.debug("Loading environments for scene: {}", sceneContext.sceneBounds);

		sceneContext.environments.clear();
		for (var environment : environments) {
			if (sceneContext.sceneBounds.intersects(environment.area.aabbs)) {
				log.debug("Added environment: {}", environment);
				sceneContext.environments.add(environment);
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
			lightningBrightness = max(lightningBrightness - brightnessChange, 0);
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
			float t = clamp(lightningBrightness, 0, 1);
			currentFogColor = mix(currentFogColor, LIGHTNING_COLOR, t);
			currentWaterColor = mix(currentWaterColor, LIGHTNING_COLOR, t);
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
			nextLightningTime += mix(MIN_QUICK_LIGHTNING_INTERVAL, MAX_QUICK_LIGHTNING_INTERVAL, RAND.nextFloat());
		} else {
			// cool-down period before a new lightning cluster
			nextLightningTime += mix(MIN_LIGHTNING_INTERVAL, MAX_LIGHTNING_INTERVAL, RAND.nextFloat());
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
