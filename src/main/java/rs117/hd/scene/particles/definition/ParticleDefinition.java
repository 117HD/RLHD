/*
 * Copyright (c) 2025, Mark7625 (https://github.com/Mark7625/)
 * All rights reserved.
 */
package rs117.hd.scene.particles.definition;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.utils.ResourcePath.path;

/**
 * Particle definition (nested sections) and loader for particles.json.
 * One singleton instance holds the definitions map and loader methods; map values are definition instances.
 */
@Slf4j
@Singleton
public class ParticleDefinition {

	public static final float NO_TARGET = -1f;

	public static final ResourcePath PARTICLES_CONFIG_PATH = Props.getFile(
		"rlhd.particles-config-path",
		() -> path(ParticleDefinition.class, "..", "particles.json")
	);

	@Inject
	transient HdPlugin plugin;
	@Inject
	transient ClientThread clientThread;

	@Getter
	private final transient Map<String, ParticleDefinition> definitions = new LinkedHashMap<>();
	private transient FileWatcher.UnregisterCallback watcher;
	@Getter
	private transient int lastDefinitionCount;
	@Getter
	private transient long lastLoadTimeMs;

	public String id;
	@Nullable
	public String description;

	public General general = new General();
	public Texture texture = new Texture();
	public Spread spread = new Spread();
	public Speed speed = new Speed();
	public Scale scale = new Scale();
	public Colours colours = new Colours();
	public Emission emission = new Emission();
	public Physics physics = new Physics();

	public boolean hasLevelBounds;
	public int startRed, endRed, deltaRed;
	public int startGreen, endGreen, deltaGreen;
	public int startBlue, endBlue, deltaBlue;
	public int startAlpha, endAlpha, deltaAlpha;
	public int scaleTransitionTicks, scaleIncrementPerTick;
	public int targetScaleRef;
	public int speedTransitionTicks, speedIncrementPerTick;
	public int alphaTransitionTicks, colourTransitionTicks;
	public int redIncrementPerTick, greenIncrementPerTick, blueIncrementPerTick, alphaIncrementPerTick;

	private static final float TICKS_TO_SEC = 64f;
	private static final float U8 = 256f * 256f;
	@Nullable
	public float[] colourIncrementPerSecond;
	public float colourTransitionSecondsConstant;
	public float scaleIncrementPerSecondCached;
	public float scaleTransitionSecondsConstant;
	public float speedIncrementPerSecondCached;
	public float speedTransitionSecondsConstant;

	@Data
	public static class General {
		public int heightOffset = 10;
		public int directionPitch = 30;
		public int directionYaw = 1024;
		public boolean displayWhenCulled;
	}

	@Data
	public static class Texture {
		@Nullable
		public String file;
		public Flipbook flipbook = new Flipbook();
	}

	@Data
	public static class Flipbook {
		public int flipbookColumns;
		public int flipbookRows;
		@Nullable
		public String flipbookMode;
	}

	@Data
	public static class Spread {
		public float yawMin;
		public float yawMax;
		public float pitchMin;
		public float pitchMax;
	}

	@Data
	public static class Speed {
		public float minSpeed;
		public float maxSpeed;
		public float targetSpeed = NO_TARGET;
		public int speedTransitionPercent = 100;
	}

	@Data
	public static class Scale {
		public float minScale;
		public float maxScale;
		public float targetScale = NO_TARGET;
		public int scaleTransitionPercent = 100;
	}

	@Data
	public static class Colours {
		public int minColourArgb;
		public int maxColourArgb;
		public int targetColourArgb;
		@Nullable
		public String minColour;
		@Nullable
		public String maxColour;
		@Nullable
		public String targetColour;
		public int colourTransitionPercent = 100;
		public int alphaTransitionPercent = 100;
		public boolean uniformColourVariation = true;
		public boolean useSceneAmbientLight = true;
	}

	@Data
	public static class Emission {
		public int minDelay;
		public int maxDelay;
		public int minSpawn;
		public int maxSpawn;
		public int initialSpawn;
		public int emissionTimeThreshold = -1;
		public int emissionCycleDuration = -1;
		public boolean emitOnlyBeforeTime = true;
		public boolean loopEmission = true;
	}

	@Data
	public static class Physics {
		public int upperBoundLevel = -2;
		public int lowerBoundLevel = -2;
		public boolean clipToTerrain = true;
		public boolean collidesWithObjects;
		public int distanceFalloffType;
		public int distanceFalloffStrength;
	}

	public void postDecode() {
		if (physics.upperBoundLevel > -2 || physics.lowerBoundLevel > -2) {
			hasLevelBounds = true;
		}
		startRed = (colours.minColourArgb >> 16) & 0xff;
		endRed = (colours.maxColourArgb >> 16) & 0xff;
		deltaRed = endRed - startRed;
		startGreen = (colours.minColourArgb >> 8) & 0xff;
		endGreen = (colours.maxColourArgb >> 8) & 0xff;
		deltaGreen = endGreen - startGreen;
		startBlue = colours.minColourArgb & 0xff;
		endBlue = colours.maxColourArgb & 0xff;
		deltaBlue = endBlue - startBlue;
		startAlpha = (colours.minColourArgb >> 24) & 0xff;
		endAlpha = (colours.maxColourArgb >> 24) & 0xff;
		deltaAlpha = endAlpha - startAlpha;

		float targetScaleVal = scale.targetScale >= 0 ? (float) scale.targetScale : NO_TARGET;
		float targetSpeedVal = speed.targetSpeed >= 0 ? (float) speed.targetSpeed : NO_TARGET;
		int maxDelay = emission.maxDelay;

		if (targetScaleVal >= 0f) {
			scaleTransitionTicks = scale.scaleTransitionPercent * maxDelay / 100;
			if (scaleTransitionTicks == 0) scaleTransitionTicks = 1;
			targetScaleRef = (int) Math.round(targetScaleVal / 4f * 16384f);
			float midScaleRef = (scale.minScale + scale.maxScale) / 2f / 4f * 16384f;
			scaleIncrementPerTick = (int) Math.round((targetScaleRef - midScaleRef) / scaleTransitionTicks);
		}
		if (targetSpeedVal >= 0f) {
			speedTransitionTicks = maxDelay * speed.speedTransitionPercent / 100;
			if (speedTransitionTicks == 0) speedTransitionTicks = 1;
			speedIncrementPerTick = (int) Math.round((targetSpeedVal - (speed.minSpeed + speed.maxSpeed) / 2f) / speedTransitionTicks);
		}
		if (colours.targetColourArgb != 0) {
			alphaTransitionTicks = colours.alphaTransitionPercent * maxDelay / 100;
			colourTransitionTicks = maxDelay * colours.colourTransitionPercent / 100;
			if (colourTransitionTicks == 0) colourTransitionTicks = 1;
			if (alphaTransitionTicks == 0) alphaTransitionTicks = 1;
			int targetRed = (colours.targetColourArgb >> 16) & 0xff;
			int targetGreen = (colours.targetColourArgb >> 8) & 0xff;
			int targetBlue = colours.targetColourArgb & 0xff;
			int targetAlpha = (colours.targetColourArgb >> 24) & 0xff;
			redIncrementPerTick = ((targetRed - (startRed + deltaRed / 2)) << 8) / colourTransitionTicks;
			greenIncrementPerTick = ((targetGreen - (startGreen + deltaGreen / 2)) << 8) / colourTransitionTicks;
			blueIncrementPerTick = ((targetBlue - (startBlue + deltaBlue / 2)) << 8) / colourTransitionTicks;
			alphaIncrementPerTick = ((targetAlpha - (startAlpha + deltaAlpha / 2)) << 8) / alphaTransitionTicks;
			redIncrementPerTick += (redIncrementPerTick <= 0 ? 4 : -4);
			greenIncrementPerTick += (greenIncrementPerTick <= 0 ? 4 : -4);
			blueIncrementPerTick += (blueIncrementPerTick <= 0 ? 4 : -4);
			alphaIncrementPerTick += (alphaIncrementPerTick <= 0 ? 4 : -4);
			colourIncrementPerSecond = new float[] {
				redIncrementPerTick * TICKS_TO_SEC / U8,
				greenIncrementPerTick * TICKS_TO_SEC / U8,
				blueIncrementPerTick * TICKS_TO_SEC / U8,
				alphaIncrementPerTick * TICKS_TO_SEC / U8
			};
			colourTransitionSecondsConstant = colourTransitionTicks / TICKS_TO_SEC;
		}
		if (targetScaleVal >= 0f) {
			scaleIncrementPerSecondCached = scaleIncrementPerTick * TICKS_TO_SEC / 16384f * 4f;
			scaleTransitionSecondsConstant = scaleTransitionTicks / TICKS_TO_SEC;
		}
		if (targetSpeedVal >= 0f) {
			speedIncrementPerSecondCached = speedIncrementPerTick * TICKS_TO_SEC / 16384f;
			speedTransitionSecondsConstant = speedTransitionTicks / TICKS_TO_SEC;
		}
	}
	
	public static int hexToArgb(String hex) {
		if (hex == null || hex.isEmpty()) return 0;
		String s = hex.startsWith("#") ? hex.substring(1) : hex;
		if (s.length() != 6 && s.length() != 8) return 0;
		try {
			int r = Integer.parseInt(s.substring(0, 2), 16);
			int g = Integer.parseInt(s.substring(2, 4), 16);
			int b = Integer.parseInt(s.substring(4, 6), 16);
			int a = s.length() == 8 ? Integer.parseInt(s.substring(6, 8), 16) : 255;
			return (a << 24) | (r << 16) | (g << 8) | b;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public void parseHexColours() {
		if (colours.minColour != null) {
			int argb = hexToArgb(colours.minColour);
			if (argb != 0) colours.minColourArgb = argb;
		}
		if (colours.maxColour != null) {
			int argb = hexToArgb(colours.maxColour);
			if (argb != 0) colours.maxColourArgb = argb;
		}
		if (colours.targetColour != null) {
			colours.targetColourArgb = hexToArgb(colours.targetColour);
		}
	}

	public static String argbToHex(int argb) {
		int r = (argb >> 16) & 0xff;
		int g = (argb >> 8) & 0xff;
		int b = argb & 0xff;
		int a = (argb >> 24) & 0xff;
		return String.format("#%02x%02x%02x%02x", r, g, b, a);
	}

	public static float[] argbToFloat(int argb) {
		return new float[] {
			((argb >> 16) & 0xff) / 255f,
			((argb >> 8) & 0xff) / 255f,
			(argb & 0xff) / 255f,
			((argb >> 24) & 0xff) / 255f
		};
	}

	public void startup(Runnable onReload) {
		watcher = PARTICLES_CONFIG_PATH.watch((p, first) -> {
			loadConfig();
			clientThread.invoke(onReload);
		});
	}

	public void shutdown() {
		if (watcher != null) {
			watcher.unregister();
			watcher = null;
		}
	}

	public void loadConfig() {
		long start = System.nanoTime();
		Gson gson = plugin.getGson();
		ParticleDefinition[] defs;
		try {
			defs = PARTICLES_CONFIG_PATH.loadJson(gson, ParticleDefinition[].class);
		} catch (IOException ex) {
			log.error("[Particles] Failed to load particles.json from {}", PARTICLES_CONFIG_PATH, ex);
			return;
		}
		definitions.clear();
		List<ParticleDefinition> ordered = new ArrayList<>();
		if (defs != null) {
			for (ParticleDefinition def : defs) {
				if (def.id != null && !def.id.isEmpty())
					def.id = def.id.toUpperCase();
				def.parseHexColours();
				def.postDecode();
				if (def.id == null || def.id.isEmpty()) {
					log.warn("[Particles] Skipping definition with missing id");
					continue;
				}
				if (definitions.put(def.id, def) != null)
					log.warn("[Particles] Duplicate particle id: {}", def.id);
				ordered.add(def);
			}
		}
		lastDefinitionCount = definitions.size();
		lastLoadTimeMs = (System.nanoTime() - start) / 1_000_000;
	}

	@Nullable
	public ParticleDefinition getDefinition(String id) {
		return definitions.get(id);
	}

	public List<String> getDefinitionIdsOrdered() {
		return new ArrayList<>(definitions.keySet());
	}

	public List<String> getAvailableTextureNames() {
		Set<String> names = new LinkedHashSet<>();
		names.add("");
		for (ParticleDefinition def : definitions.values()) {
			String file = def.texture.file;
			if (file != null && !file.isEmpty())
				names.add(file);
		}
		return new ArrayList<>(names);
	}

	@Nullable
	public String getDefaultTexturePath() {
		for (ParticleDefinition def : definitions.values()) {
			String file = def.texture.file;
			if (file != null && !file.isEmpty())
				return file;
		}
		return null;
	}
}
