package rs117.hd.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.DaylightCycle;
import rs117.hd.config.MoonBehavior;
import rs117.hd.config.MoonPhase;
import rs117.hd.config.SeasonalHemisphere;
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.scene.daylight_cycle.SkyState;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.AstronomyUtils;
import rs117.hd.utils.Camera;
import rs117.hd.utils.DeveloperTools;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.Props;
import rs117.hd.utils.ResourcePath;

import static rs117.hd.HdPlugin.SEED;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.ResourcePath.path;

/**
 * Resolves per-frame sky state, lighting, and light schedules.
 * Angles use EnvironmentManager's {@code {altitude, azimuth}} convention in radians.
 */
@Slf4j
@Singleton
public class SkyManager {
	private static final ResourcePath SKY_PRESETS_PATH = Props
		.getFile("rlhd.sky-presets-path", () -> path(SkyConfiguration.class, "sky_presets.json"));
	private static volatile Map<String, JsonObject> presetJson = Map.of();

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	private static final float NIGHT_RADIUS_BOOST_FRACTION = .25f;

	// One UTC-synchronized simulated day per real hour.
	private static final long SYNCED_DAYS_PERIOD_MS = 60L * 60 * 1000;

	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	private static final long HOUR_MS = 60L * 60 * 1000;
	// 5am–7pm occupies the first 70% of the unwarped cycle.
	private static final float NATURAL_DAY_BOUNDARY = .7f;
	private static final float ASTRONOMICAL_NIGHT_START = 19 / 24f;

	// One event per 24 simulated nights on average, lasting 20 ± 10 minutes at 2σ.
	private static final float AURORA_EVENT_CHANCE = 1f / 24;
	private static final float AURORA_EVENT_MEAN_DURATION_SECONDS = 20 * 60;
	private static final float AURORA_EVENT_DURATION_STD_DEV_SECONDS = 5 * 60;
	private static final float AURORA_EVENT_FADE_FRACTION = .2f;

	// Used by the Static moon behavior when an environment provides no moon position.
	private static final float[] DEFAULT_STATIC_MOON_ANGLES = HDUtils.sunAngles(15, 30);

	// Representative seasonal latitudes; longitude is irrelevant to the simulated clock.
	private static final double[] NORTHERN_LAT_LONG = { 52.2347902, 0.1407562 }; // Jagex office, Cambridge
	private static final double[] SOUTHERN_LAT_LONG = { -33.8472331, 150.6016524 }; // Sidney, Australia

	private static final double ANOMALISTIC_MONTH_DAYS = 27.55455;
	private static final double DRACONIC_MONTH_DAYS = 27.21222;
	private static final float LONGITUDE_LIBRATION_DEG = 7.9f;
	private static final float LATITUDE_LIBRATION_DEG = 6.7f;
	private static final float NIGHT_MOON_PHASE_TILT = -.35f;
	// Suppress sub-pixel shadow-camera movement; faster cycles use a smaller threshold.
	private static final float DIRECTIONAL_ANGLE_UPDATE_THRESHOLD = .25f * DEG_TO_RAD;

	private FileWatcher.UnregisterCallback fileWatcher;

	private long lastUpdateTime = 0;
	// Start Custom at midday.
	private double accumulatedCycleTime = .35;
	private long completedCycles = 0; // Each completed cycle = one simulated day

	private DaylightCycle configCycle;
	private float configNightFraction;
	private MoonPhase configMoonPhase;
	private MoonBehavior configMoonBehavior;
	private float configCycleDuration;
	private double[] configLatLon;

	@Nullable
	private SkyConfiguration gielinorSky;
	private Map<String, SkyConfiguration> configurations = Map.of();
	private MoonPhase fromMoonPhase = MoonPhase.REALISTIC;
	private MoonPhase toMoonPhase = MoonPhase.REALISTIC;
	@Nullable
	private float[] sunAnglesOverride;
	@Nullable
	private float[] fromSunAnglesOverride;
	private float[] moonAnglesOverride;
	private float[] fromMoonAnglesOverride;

	private Instant currentInstant;

	// Retain the frame's wall clock because currentInstant is often simulated.
	private long frameWallClockMillis;
	private Instant frameWallClockInstant;

	private float scheduleSunAltitude;
	private float previousScheduleSunAltitude = Float.NaN;
	private boolean sunDescending;
	private long scheduleNightIndex;
	private float nightFactor = 1;

	@Getter
	private boolean cycleActive;

	@Getter
	private final SkyState state = new SkyState();

	// ===== Configuration and celestial state =====================================

	public void updateConfig(HdPluginConfig config) {
		configCycle = config.daylightCycle();
		configNightFraction = clamp(config.customNightPercentage(), 0, 100) / 100f;
		configMoonBehavior = config.moonBehavior();
		configMoonPhase = config.moonPhase();
		configCycleDuration = max(1e-6f, (float) config.customCycleDurationMinutes());

		if (configCycle == DaylightCycle.DEFAULT) {
			configLatLon = NORTHERN_LAT_LONG;
		} else {
			String latLonString = config.latLon();
			double[] latLon = DeveloperTools.parseLatLon(latLonString);
			if (latLon == null) {
				if (!latLonString.isEmpty())
					log.warn("Ignoring invalid latitude & longitude coordinates: {}", latLon);

				latLon = plugin.configSeasonalHemisphere == SeasonalHemisphere.SOUTHERN ?
					SOUTHERN_LAT_LONG : NORTHERN_LAT_LONG;
			}
			configLatLon = latLon;
		}
	}

	public void startUp() {
		fileWatcher = SKY_PRESETS_PATH.watch((path, first) -> {
			try {
				loadPresets(path);
				if (!first)
					clientThread.invoke(environmentManager::reloadImmediately);
			} catch (IOException ex) {
				log.error("Failed to load sky presets:", ex);
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		configurations = Map.of();
		presetJson = Map.of();
		gielinorSky = null;
	}

	/**
	 * Whether the player has selected a daylight-cycle mode other than Off.
	 */
	public boolean isCycleConfigured() {
		return configCycle != DaylightCycle.OFF;
	}

	private void loadPresets(ResourcePath path) throws IOException {
		var gson = plugin.getGson();
		JsonArray rawPresets = path.loadJson(gson, JsonArray.class);
		if (rawPresets == null)
			throw new IOException("Empty or invalid: " + path);

		var rawPresetMap = new HashMap<String, JsonObject>();
		for (int i = 0; i < rawPresets.size(); i++) {
			JsonElement element = rawPresets.get(i);
			if (!element.isJsonObject()) {
				log.error("Sky preset at index {} is not an object", i);
				continue;
			}
			JsonObject preset = element.getAsJsonObject();
			JsonElement name = preset.get("name");
			if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
				log.error("Sky preset at index {} has no string name", i);
				continue;
			}
			if (rawPresetMap.putIfAbsent(name.getAsString(), preset) != null)
				log.error("Duplicate sky preset '{}'", name.getAsString());
		}

		var resolved = new HashMap<String, JsonObject>();
		for (String s : rawPresetMap.keySet())
			resolveSkyPreset(s, rawPresetMap, resolved, new HashSet<>());
		presetJson = Map.copyOf(resolved);

		var parsed = new HashMap<String, SkyConfiguration>();
		for (var entry : resolved.entrySet()) {
			SkyConfiguration configuration = gson.fromJson(entry.getValue(), SkyConfiguration.class).normalize();
			configuration.preset = entry.getKey();
			parsed.put(entry.getKey(), configuration);
		}
		configurations = Map.copyOf(parsed);
		gielinorSky = configurations.get(SkyConfiguration.DEFAULT_PRESET);
	}

	@Nullable
	public static JsonObject getPresetJson(String name) {
		return presetJson.get(name);
	}

	@Nullable
	private JsonObject resolveSkyPreset(
		String name,
		Map<String, JsonObject> raw,
		Map<String, JsonObject> resolved,
		HashSet<String> resolving
	) {
		JsonObject result = resolved.get(name);
		if (result != null)
			return result;
		JsonObject preset = raw.get(name);
		if (preset == null) {
			log.error("Unknown sky preset '{}'", name);
			return null;
		}
		if (!resolving.add(name)) {
			log.error("Sky preset '{}' contains a preset loop", name);
			return null;
		}
		result = new JsonObject();
		JsonElement parent = preset.get("parent");
		if (parent != null && parent.isJsonPrimitive() && parent.getAsJsonPrimitive().isString()) {
			JsonObject base = resolveSkyPreset(parent.getAsString(), raw, resolved, resolving);
			if (base != null)
				SkyConfiguration.merge(result, base);
		} else if (parent != null) {
			log.error("Sky preset '{}' has a non-string parent", name);
		}
		SkyConfiguration.merge(result, preset);
		result.remove("name");
		result.remove("parent");
		resolving.remove(name);
		resolved.put(name, result);
		return result;
	}

	@Nonnull
	SkyConfiguration getGielinorSky() {
		if (gielinorSky == null)
			throw new IllegalStateException("Missing " + SkyConfiguration.DEFAULT_PRESET + " sky preset");
		return gielinorSky;
	}

	@Nonnull
	public SkyConfiguration getSkyConfiguration(Environment environment) {
		return environment.hasSkyOverride ? environment.sky : getGielinorSky();
	}

	private static float[] anglesToSkyDirection(float altitude, float azimuth) {
		return normalize(
			sin(azimuth) * cos(altitude),
			sin(altitude),
			cos(azimuth) * cos(altitude)
		);
	}

	/**
	 * Remap a linear cycle position so night occupies the configured share.
	 */
	private double applyNightDurationWarp(double cyclePosition) {
		float dayFraction = 1 - configNightFraction;
		if (abs(dayFraction - NATURAL_DAY_BOUNDARY) < 1e-6f)
			return cyclePosition;

		if (cyclePosition < dayFraction) {
			return (cyclePosition / dayFraction) * NATURAL_DAY_BOUNDARY;
		}
		double nightProgress = (cyclePosition - dayFraction) / (1 - dayFraction);
		return NATURAL_DAY_BOUNDARY + nightProgress * (1 - NATURAL_DAY_BOUNDARY);
	}

	// ===== Sun and shadow directions =============================================

	/**
	 * Update directional shadows only after a perceptible angle change.
	 */
	public void updateDirectionalCamera(Camera directionalCamera) {
		float[] angles = state.shadowAngles;
		float[] orientation = { PI - angles[1], angles[0] };
		float diff = max(abs(angleDiff(orientation, directionalCamera.getOrientation())));
		float cycleDuration = sunAnglesOverride != null || configCycle.usesDefaultCycleTime ?
			SYNCED_DAYS_PERIOD_MS / (float) (60 * 1000) :
			configCycle == DaylightCycle.REAL_TIME ? 24 * 60 : configCycleDuration;
		if (diff >= DIRECTIONAL_ANGLE_UPDATE_THRESHOLD * saturate(cycleDuration / 300f))
			directionalCamera.setOrientation(orientation);
	}

	// ===== Aurora ================================================================

	private static float getAuroraEventRoll(long eventIndex, long salt) {
		long h = SEED + eventIndex * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		return (h >>> 40) * (1f / (1 << 24));
	}

	private float getAuroraEventStrength(double cycleTime, float eventStart) {
		long eventIndex = (long) Math.floor(cycleTime);
		if (getAuroraEventRoll(eventIndex, 0) >= AURORA_EVENT_CHANCE)
			return 0;

		double gaussian =
			Math.sqrt(-2 * Math.log(Math.max(1e-6f, getAuroraEventRoll(eventIndex, 2)))) *
			Math.cos(TWO_PI * getAuroraEventRoll(eventIndex, 3));
		float eventDuration = clamp(
			(AURORA_EVENT_MEAN_DURATION_SECONDS + (float) gaussian * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f),
			(AURORA_EVENT_MEAN_DURATION_SECONDS - 2 * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f),
			(AURORA_EVENT_MEAN_DURATION_SECONDS + 2 * AURORA_EVENT_DURATION_STD_DEV_SECONDS) / (HOUR_MS / 1000f)
		);
		float eventElapsed = (float) (cycleTime - eventIndex) - eventStart;
		if (eventElapsed < 0 || eventElapsed >= eventDuration)
			return 0;

		float fadeDuration = eventDuration * AURORA_EVENT_FADE_FRACTION;
		return
			smoothstep(0, fadeDuration, eventElapsed) *
			(1 - smoothstep(eventDuration - fadeDuration, eventDuration, eventElapsed));
	}

	private void resolveAuroraStrength() {
		double cycleTime;
		float eventStart;
		float sunAltitude = state.sunAngles[0];
		if (!configCycle.usesCustomNightDuration) {
			cycleTime = currentInstant.toEpochMilli() / (double) DAY_MS;
			eventStart = ASTRONOMICAL_NIGHT_START;
			if (configCycle.usesPresetSunAngles)
				sunAltitude = (float) AstronomyUtils.getSunAngles(currentInstant.toEpochMilli(), configLatLon)[0];
		} else {
			cycleTime = completedCycles + accumulatedCycleTime;
			eventStart = 1 - configNightFraction;
		}
		// The sky shader supplies the near-horizon fade; skip when the sun is above the horizon.
		state.auroraStrength = sunAltitude < 0 ? getAuroraEventStrength(cycleTime, eventStart) : 0;
	}

	private static float[] mirrorAngles(float[] angles) {
		return vec(-angles[0], angles[1] + PI);
	}

	private static float[] interpolateAngles(float[] from, float[] to, float[] fallback, float t) {
		if (from == null)
			from = fallback;
		if (to == null)
			to = fallback;
		return vec(mix(from[0], to[0], t), from[1] + angleDiff(to[1], from[1]) * t);
	}

	// ===== Frame update and simulated clock ======================================

	/**
	 * Map cycle position to the project's dawn- and sunset-weighted hours since midnight.
	 * The final segment extends past midnight so the result remains continuous at the cycle wrap.
	 */
	private double cyclePositionToHour(double cyclePosition) {
		// 0.0-0.15  dawn               -> 5am-7am
		// 0.15-0.35 morning            -> 7am-12pm
		// 0.35-0.55 afternoon          -> 12pm-5pm
		// 0.55-0.70 sunset             -> 5pm-7pm
		// 0.70-0.85 early night        -> 7pm-12am
		// 0.85-1.0  late night         -> 12am-5am on the following day
		if (cyclePosition < .15) {
			return 5 + cyclePosition / .15 * 2;
		} else if (cyclePosition < .35) {
			return 7 + (cyclePosition - .15) / .2 * 5;
		} else if (cyclePosition < .55) {
			return 12 + (cyclePosition - .35) / .2 * 5;
		} else if (cyclePosition < .7) {
			return 17 + (cyclePosition - .55) / .15 * 2;
		} else if (cyclePosition < .85) {
			return 19 + (cyclePosition - .7) / .15 * 5;
		} else {
			return 24 + (cyclePosition - .85) / .15 * 5;
		}
	}

	public void update() {
		resolveSkyConfiguration();

		frameWallClockMillis = System.currentTimeMillis();
		frameWallClockInstant = Instant.ofEpochMilli(frameWallClockMillis);
		currentInstant = frameWallClockInstant;
		advanceCycle(frameWallClockMillis);
		currentInstant = resolveCurrentInstant();
		resolveSkyState();
		resolveLightScheduleState();
	}

	private void resolveSkyState() {
		float[] astronomicalSunAngles = vec(AstronomyUtils.getSunAngles(currentInstant.toEpochMilli(), configLatLon));
		state.sunAngles = interpolateAngles(
			fromSunAnglesOverride, sunAnglesOverride, astronomicalSunAngles, state.configurationTransition);
		Instant moonInstant = resolveMoonInstant();
		float[] astronomicalMoonAngles = configMoonBehavior.mirrorsSun
			? mirrorAngles(state.sunAngles)
			: vec(AstronomyUtils.getMoonPosition(moonInstant.toEpochMilli(), configLatLon));
		state.moonAngles = interpolateAngles(
			fromMoonAnglesOverride, moonAnglesOverride, astronomicalMoonAngles, state.configurationTransition);
		state.sunAltitudeDegrees = state.sunAngles[0] * RAD_TO_DEG;
		state.moonAltitudeDegrees = state.moonAngles[0] * RAD_TO_DEG;
		state.sunDirection = anglesToSkyDirection(state.sunAngles[0], state.sunAngles[1]);
		state.moonDirection = anglesToSkyDirection(state.moonAngles[0], state.moonAngles[1]);
		boolean useSyntheticMoonPhase = configCycle == DaylightCycle.NIGHT || configMoonBehavior.mirrorsSun;

		boolean useAstronomicalMoonIllumination = sunAnglesOverride == null || useSyntheticMoonPhase;
		float naturalMoonIllumination = useAstronomicalMoonIllumination
			? (float) AstronomyUtils.getMoonIllumination(moonInstant.toEpochMilli())[0]
			// Fixed visible suns should determine the moon phase rendered beneath them.
			: saturate((1 - dot(state.sunDirection, state.moonDirection)) * .5f);
		float fromMoonIllumination = fromMoonPhase.isLocked ? fromMoonPhase.illumination : naturalMoonIllumination;
		float toMoonIllumination = toMoonPhase.isLocked ? toMoonPhase.illumination : naturalMoonIllumination;
		state.moonIllumination = mix(fromMoonIllumination, toMoonIllumination, state.configurationTransition);
		if (state.moonVisibility == 0)
			state.moonIllumination = 0;
		state.shadowAngles = state.sunAngles[0] < 0 && state.moonAngles[0] > 0 && state.moonIllumination > 0
			? state.moonAngles
			: state.sunAngles;
		float[] moonPhaseLightDirection;
		if (useSyntheticMoonPhase) {
			moonPhaseLightDirection = getSyntheticMoonPhaseLightDirection(moonInstant);
		} else {
			moonPhaseLightDirection = state.sunDirection;
		}
		if (state.configurationTransition == 0) {
			state.moonPhaseLightDirection = moonPhaseLightDirection;
			state.moonPhaseReversed = fromMoonPhase.reversesTerminator;
		} else if (state.configurationTransition == 1) {
			state.moonPhaseLightDirection = moonPhaseLightDirection;
			state.moonPhaseReversed = toMoonPhase.reversesTerminator;
		} else {
			float fromSign = fromMoonPhase.reversesTerminator ? -1 : 1;
			float toSign = toMoonPhase.reversesTerminator ? -1 : 1;
			if (fromSign == toSign) {
				state.moonPhaseLightDirection = multiply(moonPhaseLightDirection, fromSign);
			} else {
				float[] phaseTangent = cross(state.moonDirection, moonPhaseLightDirection);
				if (dot(phaseTangent, phaseTangent) < 1e-6f)
					phaseTangent = cross(state.moonDirection, abs(state.moonDirection[1]) < .999f ? vec(0, 1, 0) : vec(0, 0, 1));
				state.moonPhaseLightDirection = normalize(add(
					multiply(moonPhaseLightDirection, fromSign * cos(PI * state.configurationTransition)),
					multiply(normalize(phaseTangent), sin(PI * state.configurationTransition))
				));
			}
			state.moonPhaseReversed = false;
		}
		// Approximate the Moon's visible east/west and north/south rocking over a month.
		if (moonAnglesOverride != null || configMoonBehavior.mirrorsSun) {
			state.moonLibration = vec(0, 0);
		} else {
			double days = moonInstant.toEpochMilli() / (double) DAY_MS;
			state.moonLibration = vec(
				sin((float) (days / ANOMALISTIC_MONTH_DAYS) * TWO_PI) * LONGITUDE_LIBRATION_DEG * DEG_TO_RAD,
				sin((float) (days / DRACONIC_MONTH_DAYS) * TWO_PI) * LATITUDE_LIBRATION_DEG * DEG_TO_RAD
			);
		}
		state.celestialPole = anglesToSkyDirection((float) configLatLon[0] * DEG_TO_RAD, 0);
		state.celestialRotation = (currentInstant.toEpochMilli() % DAY_MS) / (float) DAY_MS * TWO_PI;
		resolveAuroraStrength();
	}

	/**
	 * Keep Night and mirrored moons on a fixed diagonal phase orbit around the moon.
	 */
	private float[] getSyntheticMoonPhaseLightDirection(Instant moonInstant) {
		float[] moonUp = abs(state.moonDirection[1]) < .999f ? vec(0, 1, 0) : vec(0, 0, 1);
		float[] moonRight = normalize(cross(moonUp, state.moonDirection));
		moonUp = normalize(cross(state.moonDirection, moonRight));
		float[] orbitTangent = normalize(add(moonRight, multiply(moonUp, NIGHT_MOON_PHASE_TILT)));
		float phaseCos = state.moonIllumination * 2 - 1;
		float phaseSin = sqrt(max(0, 1 - phaseCos * phaseCos));
		if (sin((float) AstronomyUtils.getMoonIllumination(moonInstant.toEpochMilli())[1] * TWO_PI) < 0)
			phaseSin = -phaseSin;
		return normalize(add(multiply(state.moonDirection, phaseCos), multiply(orbitTangent, phaseSin)));
	}

	private void resolveSkyConfiguration() {
		Environment from = environmentManager.getFromEnvironment();
		Environment to = environmentManager.getToEnvironment();
		state.fromConfiguration = getSkyConfiguration(from);
		state.toConfiguration = getSkyConfiguration(to);
		state.configurationTransition = environmentManager.getTransitionProgress();
		float fromMoonStrength = state.fromConfiguration.moonDirectionalStrength;
		if (fromMoonStrength < 0)
			fromMoonStrength = from.directionalStrength;
		float toMoonStrength = state.toConfiguration.moonDirectionalStrength;
		if (toMoonStrength < 0)
			toMoonStrength = to.directionalStrength;
		state.moonDirectionalStrength = mix(fromMoonStrength, toMoonStrength, state.configurationTransition);
		SkyConfiguration fromSky = state.fromConfiguration;
		SkyConfiguration toSky = state.toConfiguration;
		fromMoonPhase = fromSky.forceMoonPhase != null ? fromSky.forceMoonPhase : configMoonPhase;
		toMoonPhase = toSky.forceMoonPhase != null ? toSky.forceMoonPhase : configMoonPhase;
		fromSunAnglesOverride = getSunAnglesOverride(fromSky);
		sunAnglesOverride = getSunAnglesOverride(toSky);
		fromMoonAnglesOverride = getMoonAnglesOverride(fromSky);
		moonAnglesOverride = getMoonAnglesOverride(toSky);
		float fromMoonVisibility = isMoonHidden(fromSky) ? 0 : fromSky.moonVisibility;
		float toMoonVisibility = isMoonHidden(toSky) ? 0 : toSky.moonVisibility;
		state.moonVisibility = mix(fromMoonVisibility, toMoonVisibility, state.configurationTransition);
		cycleActive = environmentManager.getTargetEnvironment().isOverworld && isCycleConfigured();
	}

	@Nullable
	private float[] getSunAnglesOverride(SkyConfiguration sky) {
		float[] angles = sky.sunAngles;
		if (angles == null && configCycle.skyPreset != null) {
			SkyConfiguration cycleSky = configurations.get(configCycle.skyPreset);
			if (cycleSky != null)
				angles = cycleSky.sunAngles;
		}
		return isCycleConfigured() && angles != null && (sky.sunAngles != null || configCycle.usesPresetSunAngles) ? angles : null;
	}

	@Nullable
	private float[] getMoonAnglesOverride(SkyConfiguration sky) {
		return sky.moonAngles != null ? sky.moonAngles : configMoonBehavior.isStatic ? DEFAULT_STATIC_MOON_ANGLES : null;
	}

	private boolean isMoonHidden(SkyConfiguration sky) {
		return sky.hideMoon || configMoonBehavior.isDisabled && sky.forceMoonPhase == null;
	}

	private void advanceCycle(long currentTimeMillis) {
		if (lastUpdateTime == 0)
			lastUpdateTime = currentTimeMillis;

		double cycleDurationMillis = configCycleDuration * 60.0 * 1000.0;
		long elapsedMillis = currentTimeMillis - lastUpdateTime;
		accumulatedCycleTime += elapsedMillis / cycleDurationMillis;
		long cyclesElapsed = (long) accumulatedCycleTime;
		if (cyclesElapsed > 0) {
			accumulatedCycleTime -= cyclesElapsed;
			completedCycles += cyclesElapsed;
		}
		lastUpdateTime = currentTimeMillis;
	}

	private Instant resolveCurrentInstant() {
		if (sunAnglesOverride != null || configCycle.usesDefaultCycleTime)
			return getDefaultInstant();

		switch (configCycle) {
			case OFF:
			case REAL_TIME:
				return frameWallClockInstant;
			case CUSTOM:
				// Custom night duration controls the cycle's night share before low-sun-weighted mapping.
				double cyclePosition = applyNightDurationWarp(accumulatedCycleTime);
				double mappedHour = cyclePositionToHour(cyclePosition);
				Instant startOfDay = frameWallClockInstant.truncatedTo(ChronoUnit.DAYS)
					.plus(completedCycles, ChronoUnit.DAYS);
				return startOfDay.plusMillis((long) (mappedHour * HOUR_MS));
		}

		throw new IllegalStateException("Unhandled daylight cycle mode: " + configCycle);
	}

	/**
	 * A full UTC-synchronized day per real hour, independent of Custom settings.
	 */
	private Instant getDefaultInstant() {
		double cyclePosition = (frameWallClockMillis % SYNCED_DAYS_PERIOD_MS) / (double) SYNCED_DAYS_PERIOD_MS;
		long day = frameWallClockMillis / SYNCED_DAYS_PERIOD_MS;
		return Instant.EPOCH.plus(day, ChronoUnit.DAYS)
			.plusMillis((long) (cyclePositionToHour(cyclePosition) * HOUR_MS));
	}

	private Instant resolveMoonInstant() {
		if (sunAnglesOverride != null || !configCycle.usesCustomNightDuration)
			return currentInstant;

		double cyclePosition = applyNightDurationWarp(accumulatedCycleTime);
		long offsetMillis = (long) ((completedCycles + cyclePosition) * DAY_MS);
		return frameWallClockInstant.truncatedTo(ChronoUnit.DAYS).plusMillis(offsetMillis);
	}

	// ===== Light schedule ========================================================

	private void resolveLightScheduleState() {
		scheduleSunAltitude = state.sunAltitudeDegrees;
		sunDescending = Float.isNaN(previousScheduleSunAltitude) || scheduleSunAltitude <= previousScheduleSunAltitude;
		previousScheduleSunAltitude = scheduleSunAltitude;
		// Change offsets at noon, keeping each dusk-to-dawn schedule stable through midnight.
		scheduleNightIndex = Math.floorDiv(currentInstant.toEpochMilli() - DAY_MS / 2, DAY_MS);
		if (cycleActive)
			nightFactor = smoothstep(5, -18, scheduleSunAltitude);
	}

	public void prepareLightSchedule(Light light) {
		light.daylightCycleActivation = 1;
		if (light.def.schedule == null)
			return;

		light.daylightCycleActivation = getScheduleActivation(light);
		if (light.daylightCycleActivation < .001f)
			light.visible = false;
	}

	public void applyLightSchedule(Light light) {
		if (!cycleActive && light.def.schedule == null)
			return;

		light.strength *= getNightStrengthScale(light);
		light.radius *= getNightRadiusScale(light);
	}

	private float getScheduleActivation(Light light) {
		if (light.def.schedule == null)
			return 1;
		if (!isCycleConfigured())
			return 0;

		float randomOffset = (getScheduleRandomOffset(light) * 2 - 1) * light.def.schedule.randomOffset;
		return light.def.schedule.getActivation(scheduleSunAltitude, sunDescending, randomOffset);
	}

	private float getScheduleRandomOffset(Light light) {
		int hash = Float.floatToIntBits(light.pos[0]);
		hash ^= Float.floatToIntBits(light.pos[1]) * 374761393;
		hash ^= Float.floatToIntBits(light.pos[2]) * 668265263;
		hash ^= light.plane * 912271;
		hash ^= Long.hashCode(scheduleNightIndex) * 104395301;
		hash ^= hash >>> 16;
		hash *= 0x85ebca6b;
		hash ^= hash >>> 13;
		hash *= 0xc2b2ae35;
		hash ^= hash >>> 16;
		return (hash & 0x7FFFFFFF) / 2147483647f;
	}

	public float getNightStrengthScale(Light light) {
		float nightScale = cycleActive ? mix(1, light.def.nightMultiplier, nightFactor) : 1;
		return nightScale * light.daylightCycleActivation;
	}

	public float getNightRadiusScale(Light light) {
		float multiplier = light.def.nightMultiplier;
		if (!cycleActive)
			return light.daylightCycleActivation;
		if (multiplier <= 0)
			return light.def.schedule != null ? 0 : mix(1, 0, nightFactor);

		// Unscheduled lights retain their authored culling radius unless boosted at night.
		return light.daylightCycleActivation * mix(1, multiplier, nightFactor * NIGHT_RADIUS_BOOST_FRACTION);
	}
}
