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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
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
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;
import rs117.hd.utils.VariableSupplier;

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

	@Inject
	private GamevalManager gamevalManager;

	private final Map<String, Integer> varbitConditionVars = new HashMap<>();
	private final Map<String, Integer> varpConditionVars = new HashMap<>();
	private final VariableSupplier varbitVariableSupplier = name -> {
		Integer id = varbitConditionVars.get(name);
		return id == null ? null : client.getVarbitValue(id);
	};
	private final VariableSupplier varpVariableSupplier = name -> {
		Integer id = varpConditionVars.get(name);
		return id == null ? null : client.getVarpValue(id);
	};

	private static final float TRANSITION_DURATION = 3; // seconds
	// distance in tiles to skip transition (e.g. entering cave, teleporting)
	// walking across a loading line causes a movement of 40-41 tiles
	private static final int SKIP_TRANSITION_DISTANCE = 41;

	// when the current transition began, relative to plugin startup
	private boolean transitionComplete = true;
	@Getter
	private float transitionProgress = 1;
	private double transitionStartTime = 0;
	private int[] previousPosition = new int[3];

	private static final class State {
		final Environment current = Environment.DEFAULT.copy();
		final Environment from = Environment.DEFAULT.copy();
		final Environment to = Environment.DEFAULT.copy();
		Environment target = Environment.NONE;
	}

	private final State state = new State();

	private boolean lightningEnabled = false;
	private boolean forceNextTransition = false;
	private boolean forceNextTransitionInstant;

	private Environment[] environments = {};
	private FileWatcher.UnregisterCallback fileWatcher;

	public void startUp() {
		fileWatcher = ENVIRONMENTS_PATH.watch((path, first) -> {
			try (var gamevals = gamevalManager.obtainHandle()) {
				environments = loadEnvironments(path);
				log.debug("Loaded {} environments", environments.length);

				if (!config.legacyTobEnvironment()) {
					var legacyEnvs = List.of("TOB_ROOM_VAULT_LEGACY", "THEATRE_OF_BLOOD_LEGACY");
					environments = Arrays.stream(environments)
						.filter(env -> env.key == null || !legacyEnvs.contains(env.key))
						.toArray(Environment[]::new);
				}

				if (!config.pohThemeEnvironments())
					environments = Arrays.stream(environments)
						.filter(env -> !env.isPohTheme)
						.toArray(Environment[]::new);

				HashMap<String, Environment> map = new HashMap<>();
				for (var env : environments)
					if (env.key != null)
						map.put(env.key, env);

				Environment.OVERWORLD = map.getOrDefault("OVERWORLD", Environment.DEFAULT);
				Environment.AUTUMN = map.getOrDefault("AUTUMN", Environment.DEFAULT);
				Environment.WINTER = map.getOrDefault("WINTER", Environment.DEFAULT);

				for (var env : environments)
					env.normalize();

				bindConditionVars(gamevals);

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

	private Environment[] loadEnvironments(ResourcePath path) throws IOException {
		Environment[] loaded = path.loadJson(plugin.getGson(), Environment[].class);
		if (loaded == null)
			throw new IOException("Empty or invalid: " + path);
		return loaded;
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		environments = new Environment[0];
		reset();
	}

	public void reset() {
		state.target = Environment.NONE;
		forceNextTransition = false;
		forceNextTransitionInstant = false;
	}

	public void reload() {
		reload(true);
	}

	public void reloadAndSmoothlyTransition() {
		reload(false);
	}

	private void reload(boolean instantTransition) {
		var previous = state.target;
		shutDown();
		startUp();
		forceNextTransition = true;
		forceNextTransitionInstant = instantTransition;
		state.target = previous;
	}

	private void bindConditionVars(GamevalManager.Handle gamevals) {
		varbitConditionVars.clear();
		varpConditionVars.clear();
		if (environments == null)
			return;

		for (var env : environments) {
			bindConditionVars(env.varbitCondition, gamevals.getVarbits(), varbitConditionVars, "varbit");
			bindConditionVars(env.varpCondition, gamevals.getVarps(), varpConditionVars, "varp");
		}
	}

	private void bindConditionVars(
		ExpressionPredicate condition,
		Map<String, Integer> gamevals,
		Map<String, Integer> bindings,
		String kind
	) {
		if (!(condition instanceof ExpressionParser.SerializableExpressionPredicate))
			return;
		var expr = ((ExpressionParser.SerializableExpressionPredicate) condition).expression;
		for (String name : expr.variables) {
			if (bindings.containsKey(name))
				continue;
			Integer id = gamevals.get(name.toUpperCase());
			if (id == null) {
				log.error("Unknown {} condition variable '{}'", kind, name, new Throwable());
				continue;
			}
			bindings.put(name, id);
		}
	}

	private boolean isConditionSatisfied(Environment environment) {
		return
			environment.varbitCondition.test(varbitVariableSupplier) &&
			environment.varpCondition.test(varpVariableSupplier);
	}

	/**
	 * Resolve and interpolate the environment at the camera's focal point.
	 */
	public void update(SceneContext sceneContext) {
		assert client.isClientThread();

		int[] focalPoint = sceneContext.localToWorld(
			plugin.cameraFocalPoint[0],
			plugin.cameraFocalPoint[1],
			client.getTopLevelWorldView().getPlane()
		);

		// skip the transitional fade if the player has moved too far
		// since the previous frame. results in an instant transition when
		// teleporting, entering dungeons, etc.
		int tileChange = (int) max(abs(subtract(vec(focalPoint), vec(previousPosition))));
		previousPosition = focalPoint;

		boolean skipTransition = tileChange >= SKIP_TRANSITION_DISTANCE;
		for (int i = 0; i < sceneContext.environments.size(); i++) {
			Environment environment = sceneContext.environments.get(i);
			if (!environment.area.containsPoint(focalPoint))
				continue;
			if (!isConditionSatisfied(environment))
				continue;
			changeEnvironment(environment, skipTransition);
			break;
		}

		// Update every frame, since other plugins may control it.
		updateTargetSkyColor(getResolvedTargetEnvironment());

		if (transitionComplete) {
			// Always write fog and water color, since they're affected by lightning
			copyTo(state.current.getFogColor(), state.to.getFogColor());
			copyTo(state.current.getWaterColor(), state.to.getWaterColor());
		} else {
			transitionProgress = smoothstep(0, 1, (float) (plugin.elapsedTime - transitionStartTime) / TRANSITION_DURATION);
			state.current.interpolate(state.from, state.to, transitionProgress);
			if (transitionProgress == 1)
				transitionComplete = true;
		}

		updateLightning();
	}

	/**
	 * Begin a transition to {@code newEnvironment}.
	 */
	private void changeEnvironment(Environment newEnvironment, boolean skipTransition) {
		// Skip changing the environment unless the transition is forced, since reapplying
		// the overworld environment is required when switching between seasonal themes
		if (state.target == newEnvironment && !forceNextTransition)
			return;

		if (state.target == Environment.NONE) {
			skipTransition = true;
		} else if (forceNextTransition) {
			forceNextTransition = false;
			skipTransition = forceNextTransitionInstant;
			forceNextTransitionInstant = false;
		}

		if (state.target.instantTransition || newEnvironment.instantTransition)
			skipTransition = true;

		log.debug("changing environment from {} to {} (instant: {})", state.target, newEnvironment, skipTransition);
		state.target = newEnvironment;
		transitionComplete = false;
		transitionProgress = 0;
		transitionStartTime = plugin.elapsedTime - (skipTransition ? TRANSITION_DURATION : 0);

		state.current.copyTo(state.from);
		mod(state.from.getShadowAngles(), state.current.getShadowAngles(), TWO_PI);

		Environment areaEnvironment = getResolvedTargetEnvironment();
		Environment lightingEnvironment = areaEnvironment;
		if (!config.atmosphericLighting() && !lightingEnvironment.force)
			lightingEnvironment = getOverworldEnvironment();
		lightingEnvironment.copyTo(state.to);
		state.to.fogDepth = areaEnvironment.fogDepth;
		state.to.groundFogStart = areaEnvironment.groundFogStart;
		state.to.groundFogEnd = areaEnvironment.groundFogEnd;
		state.to.groundFogOpacity = areaEnvironment.groundFogOpacity;
		lightningEnabled = areaEnvironment.lightningEffects;

		copyTo(state.to.getShadowAngles(), areaEnvironment.getShadowAngles());
		updateTargetSkyColor(areaEnvironment);

		// Prevent transitions from taking the long way around
		for (int i = 0; i < 2; i++) {
			float diff = state.from.getShadowAngles()[i] - state.to.getShadowAngles()[i];
			if (abs(diff) > PI)
				state.to.getShadowAngles()[i] += TWO_PI * sign(diff);
		}
	}

	private void updateTargetSkyColor(Environment env) {
		copyTo(state.to.getFogColor(), getFogColor(env));
		if (usesDefaultSkyColor(env)) {
			DefaultSkyColor sky = plugin.configDefaultSkyColor;
			if (sky == DefaultSkyColor.OSRS)
				sky = DefaultSkyColor.DEFAULT;
			copyTo(state.to.getWaterColor(), sky.getRgb(client));
		} else {
			copyTo(state.to.getWaterColor(), env.getFogColor());
		}

		// Override with decoupled water/sky color if present
		if (env.hasWaterColorOverride) {
			copyTo(state.to.getWaterColor(), env.getWaterColor());
		} else if (config.decoupleSkyAndWaterColor()) {
			copyTo(state.to.getWaterColor(), DefaultSkyColor.DEFAULT.getRgb(client));
		}
	}

	private boolean usesDefaultSkyColor(Environment env) {
		return !env.hasFogColorOverride || env.allowSkyOverride && plugin.configOverrideSky;
	}

	public float[] getFogColor(Environment env) {
		return usesDefaultSkyColor(env) ? plugin.configDefaultSkyColor.getRgb(client) : env.getFogColor();
	}

	/**
	 * Add the environments which can intersect the current scene.
	 */
	public void loadSceneEnvironments(SceneContext sceneContext) {
		log.debug("Loading environments for scene: {}", sceneContext.sceneBounds);

		sceneContext.environments.clear();
		for (int i = 0; i < environments.length; i++) {
			Environment environment = environments[i];
			if (sceneContext.sceneBounds.intersects(environment.area.aabbs)) {
				log.debug("Added environment: {}", environment);
				sceneContext.environments.add(environment);
			}
		}

		// Fall back to the default environment
		sceneContext.environments.add(Environment.DEFAULT);
	}

	/* lightning */
	private static final float[] LIGHTNING_COLOR = { .25f, .25f, .25f };
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
			mix(state.current.getFogColor(), state.current.getFogColor(), LIGHTNING_COLOR, t);
			mix(state.current.getWaterColor(), state.current.getWaterColor(), LIGHTNING_COLOR, t);
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

	private Environment getResolvedTargetEnvironment() {
		if (state.target == Environment.OVERWORLD)
			return getOverworldEnvironment();
		return state.target;
	}

	Environment getFromEnvironment() {
		return state.from;
	}

	Environment getToEnvironment() {
		return state.to;
	}

	public Environment getCurrentEnvironment() {
		return state.current;
	}

	public Environment getTargetEnvironment() {
		return state.target;
	}

	public Environment getOverworldEnvironment() {
		switch (plugin.configSeasonalTheme) {
			case AUTUMN:
				return Environment.AUTUMN;
			case WINTER:
				return Environment.WINTER;
			default:
				return Environment.OVERWORLD;
		}
	}

	@Nullable
	public Environment getEnvironmentAt(int[] worldPos) {
		for (int i = 0; i < environments.length; i++) {
			Environment env = environments[i];
			if (env.area.containsPoint(worldPos) && isConditionSatisfied(env))
				return env;
		}
		return null;
	}
}
