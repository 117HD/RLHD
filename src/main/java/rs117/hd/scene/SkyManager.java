package rs117.hd.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
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
import rs117.hd.scene.daylight_cycle.SkyConfiguration;
import rs117.hd.scene.daylight_cycle.SkyState;
import rs117.hd.scene.environments.Environment;
import rs117.hd.scene.lights.Light;
import rs117.hd.utils.AstronomyUtils;
import rs117.hd.utils.Camera;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.FileWatcher;
import rs117.hd.utils.GsonUtils;
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
	public static Map<String, SkyConfiguration> PRESETS = Map.of();
	public static final double[] DEFAULT_LATLON = { 52.2347902, .1407562 }; // Jagex's offices, Cambridge

	private static final String DEFAULT_PRESET_NAME = "GIELINOR";
	private static final ResourcePath SKY_PRESETS_PATH = Props
		.getFile("rlhd.sky-presets-path", () -> path(SkyConfiguration.class, "sky_presets.json"));

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
	private static final float ASTRONOMICAL_NIGHT_START = 19 / 24f;

	// One event per 24 simulated nights on average, lasting 20 ± 10 minutes at 2σ.
	private static final float AURORA_EVENT_CHANCE = 1f / 24;
	private static final float AURORA_EVENT_MEAN_DURATION_SECONDS = 20 * 60;
	private static final float AURORA_EVENT_DURATION_STD_DEV_SECONDS = 5 * 60;
	private static final float AURORA_EVENT_FADE_FRACTION = .2f;

	private static final float BASIC_SUN_TILT = 23.5f * DEG_TO_RAD;
	private static final float BASIC_MOON_PHASE_PERIOD_DAYS = 29.53059f;

	// Used by the Static moon behavior when an environment provides no moon position.
	private static final float[] DEFAULT_STATIC_MOON_ANGLES = HDUtils.sunAngles(15, 30);

	private static final double ANOMALISTIC_MONTH_DAYS = 27.55455;
	private static final double DRACONIC_MONTH_DAYS = 27.21222;
	private static final float LONGITUDE_LIBRATION_DEG = 7.9f;
	private static final float LATITUDE_LIBRATION_DEG = 6.7f;
	private static final float NIGHT_MOON_PHASE_TILT = -.35f;
	// Suppress sub-pixel shadow-camera movement; faster cycles use a smaller threshold.
	private static final float DIRECTIONAL_ANGLE_UPDATE_THRESHOLD = .25f * DEG_TO_RAD;

	private FileWatcher.UnregisterCallback fileWatcher;

	private DaylightCycle configCycle;
	private float configNightFraction;
	private MoonPhase configMoonPhase;
	private MoonBehavior configMoonBehavior;
	private float configCycleDuration;
	private final double[] configLatLon = new double[2];

	private MoonPhase fromMoonPhase = MoonPhase.REALISTIC;
	private MoonPhase toMoonPhase = MoonPhase.REALISTIC;
	@Nullable
	private float[] sunAnglesOverride;
	@Nullable
	private float[] fromSunAnglesOverride;
	private float[] moonAnglesOverride;
	private float[] fromMoonAnglesOverride;

	private Instant currentInstant;

	private long frameUtcMillis;
	private Instant frameUtcInstant;

	private double customCycleElapsedDays = .35;

	private float scheduleSunAltitude;
	private float previousScheduleSunAltitude = Float.NaN;
	private boolean sunDescending;
	private long scheduleNightIndex;
	private float nightFactor = 1;

	@Getter
	private final SkyState state = new SkyState();

	// Lifecycle and configuration

	public void startUp() {
		fileWatcher = SKY_PRESETS_PATH.watch((path, first) -> {
			try {
				loadPresets(path);
				if (!first)
					clientThread.invoke(environmentManager::reload);
			} catch (IOException ex) {
				log.error("Failed to load sky presets:", ex);
			}
		});
	}

	public void shutDown() {
		if (fileWatcher != null)
			fileWatcher.unregister();
		fileWatcher = null;
		SkyConfiguration.DEFAULT_PRESET = null;
		PRESETS = Map.of();
	}

	private void loadPresets(ResourcePath path) throws IOException {
		var gson = plugin.getGson();
		JsonArray rawDefinitions = path.loadJson(gson, JsonArray.class);
		if (rawDefinitions == null)
			throw new IOException("Empty or invalid: " + path);

		var rawDefinitionMap = new HashMap<String, JsonObject>();
		for (int i = 0; i < rawDefinitions.size(); i++) {
			JsonElement element = rawDefinitions.get(i);
			if (!element.isJsonObject()) {
				log.error("Sky preset at index {} is not an object", i);
				continue;
			}
			JsonObject definition = element.getAsJsonObject();
			JsonElement name = definition.get("name");
			if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
				log.error("Sky preset at index {} lacks a name", i);
				continue;
			}
			if (rawDefinitionMap.putIfAbsent(name.getAsString(), definition) != null)
				log.error("Ignoring duplicate sky preset '{}' at index {}", name.getAsString(), i);
		}

		var resolvedDefinitions = GsonUtils.resolveParentDefinitions(
			rawDefinitionMap, "sky preset", null, GsonUtils::deepInheritFrom);

		var allPresets = new HashMap<String, SkyConfiguration>();
		for (var entry : resolvedDefinitions.entrySet()) {
			String name = entry.getKey();
			try {
				var preset = gson.fromJson(entry.getValue(), SkyConfiguration.class);
				preset.normalize();
				allPresets.put(name, preset);
			} catch (RuntimeException ex) {
				log.error("Ignoring invalid sky preset '{}': {}", name, ex.getMessage());
			}
		}

		SkyConfiguration preset = allPresets.get(DEFAULT_PRESET_NAME);
		if (preset == null)
			throw new IOException("Missing or invalid " + DEFAULT_PRESET_NAME + " sky preset");

		PRESETS = Map.copyOf(allPresets);
		SkyConfiguration.DEFAULT_PRESET = preset;
	}

	public void updateConfig(HdPluginConfig config) {
		configCycle = config.daylightCycle();
		configMoonBehavior = config.moonBehavior();
		if (configMoonBehavior == MoonBehavior.REALISTIC && configCycle == DaylightCycle.CUSTOM_BASIC)
			configMoonBehavior = MoonBehavior.MIRRORED;
		configMoonPhase = config.moonPhase();
		configCycleDuration = max(1e-6f, (float) config.customCycleDurationMinutes());
		configNightFraction = clamp(config.basicNightPercentage(), 0, 100) / 100f;

		if (configCycle == DaylightCycle.REAL_TIME || configCycle == DaylightCycle.CUSTOM_REALISTIC) {
			configLatLon[0] = clamp(config.latitude(), -90, 90);
			configLatLon[1] = clamp(config.longitude(), -180, 180);
		} else {
			configLatLon[0] = DEFAULT_LATLON[0];
			configLatLon[1] = DEFAULT_LATLON[1];
		}
	}

	public boolean isCycleDisabled() {
		return configCycle == DaylightCycle.OFF;
	}

	// Frame resolution

	/** Resolve celestial and schedule state before lights are updated; SkyRenderer.prepareFrame completes lighting. */
	public void update() {
		resolveSkyConfiguration();

		frameUtcMillis = System.currentTimeMillis();
		frameUtcInstant = Instant.ofEpochMilli(frameUtcMillis);
		if (configCycle.usesCustomCycleTime)
			advanceCustomCycle();
		currentInstant = resolveCurrentInstant(sunAnglesOverride);
		resolveSkyState();
		resolveLightScheduleState();
	}

	private void resolveSkyConfiguration() {
		Environment from = environmentManager.getFromEnvironment();
		Environment to = environmentManager.getToEnvironment();
		state.fromConfiguration = from.getSky();
		state.toConfiguration = to.getSky();
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
		state.cycleActive = environmentManager.getTargetEnvironment().isOverworld && !isCycleDisabled();
	}

	@Nullable
	private float[] getSunAnglesOverride(SkyConfiguration sky) {
		float[] angles = sky.sunAngles;
		if (angles == null && configCycle.skyPreset != null) {
			SkyConfiguration cycleSky = PRESETS.get(configCycle.skyPreset);
			if (cycleSky != null)
				angles = cycleSky.sunAngles;
		}
		return isCycleDisabled() ? null : angles;
	}

	@Nullable
	private float[] getMoonAnglesOverride(SkyConfiguration sky) {
		return sky.moonAngles != null ? sky.moonAngles : configMoonBehavior.isStatic ? DEFAULT_STATIC_MOON_ANGLES : null;
	}

	private boolean isMoonHidden(SkyConfiguration sky) {
		return sky.hideMoon || configMoonBehavior.isDisabled && sky.forceMoonPhase == null;
	}

	// Cycle clocks

	private Instant resolveCurrentInstant(@Nullable float[] sunOverride) {
		// Fixed environment angles use Default's slow synchronized time as a stable tuning baseline.
		if (configCycle.usesDefaultCycleTime || sunOverride != null && configCycle != DaylightCycle.CUSTOM_BASIC)
			return getDefaultInstant();

		switch (configCycle) {
			case OFF:
			case REAL_TIME:
				return frameUtcInstant;
			case CUSTOM_REALISTIC:
				Instant startOfDay = frameUtcInstant.truncatedTo(ChronoUnit.DAYS)
					.plus(floor(customCycleElapsedDays), ChronoUnit.DAYS);
				return startOfDay.plusMillis((long) (fract(customCycleElapsedDays) * DAY_MS));
			case CUSTOM_BASIC:
				float cyclePosition = applyBasicNightDurationWarp((float) fract(customCycleElapsedDays));
				return Instant.EPOCH.plus(floor(customCycleElapsedDays), ChronoUnit.DAYS)
					.plusMillis((long) (cyclePosition * DAY_MS));
		}

		throw new IllegalStateException("Unhandled daylight cycle mode: " + configCycle);
	}

	/**
	 * A full UTC-synchronized day per real hour, independent of Custom settings.
	 */
	private Instant getDefaultInstant() {
		double cyclePosition = (frameUtcMillis % SYNCED_DAYS_PERIOD_MS) / (double) SYNCED_DAYS_PERIOD_MS;
		long day = frameUtcMillis / SYNCED_DAYS_PERIOD_MS;
		return Instant.EPOCH.plus(day, ChronoUnit.DAYS)
			.plusMillis((long) (cyclePosition * DAY_MS));
	}

	private void advanceCustomCycle() {
		double cycleDurationMillis = configCycleDuration * 60.0 * 1000.0;
		customCycleElapsedDays += plugin.deltaTimeMs / cycleDurationMillis;
	}

	/**
	 * Remap a linear basic cycle so night occupies the configured share without changing speed abruptly.
	 */
	private float applyBasicNightDurationWarp(float cyclePosition) {
		float dayFraction = 1 - configNightFraction;
		if (dayFraction <= 0)
			return .5f + cyclePosition * .5f;
		if (dayFraction >= 1)
			return cyclePosition * .5f;
		if (abs(dayFraction - .5f) < 1e-6f)
			return cyclePosition;

		float daySlope = .5f / dayFraction;
		float nightSlope = .5f / (1 - dayFraction);
		float slope = min(1, 3 * min(daySlope, nightSlope));
		boolean isDay = cyclePosition < dayFraction;
		float fromPosition = isDay ? 0 : dayFraction;
		float length = (isDay ? dayFraction : 1) - fromPosition;
		float t = (cyclePosition - fromPosition) / length;
		return (isDay ? 0 : .5f) +
			   .5f * t * t * (3 - 2 * t) +
			   length * slope * t * (1 - t) * (1 - 2 * t);
	}

	// Celestial state

	private void resolveSkyState() {
		// Resolve the celestial positions, applying any environment overrides through the transition.
		long currentMillis = currentInstant.toEpochMilli();
		float[] astronomicalSunAngles = configCycle == DaylightCycle.CUSTOM_BASIC
			? getBasicSunAngles()
			: vec(AstronomyUtils.getSunAngles(currentMillis, configLatLon));
		state.sunAngles = interpolateAngles(
			fromSunAnglesOverride, sunAnglesOverride, astronomicalSunAngles, state.configurationTransition);
		float[] astronomicalMoonAngles = getMovingMoonAngles(currentMillis, state.sunAngles);
		state.moonAngles = interpolateAngles(
			fromMoonAnglesOverride, moonAnglesOverride, astronomicalMoonAngles, state.configurationTransition);
		state.sunAltitudeDegrees = state.sunAngles[0] * RAD_TO_DEG;
		state.moonAltitudeDegrees = state.moonAngles[0] * RAD_TO_DEG;
		state.sunDirection = anglesToSkyDirection(state.sunAngles[0], state.sunAngles[1]);
		state.moonDirection = anglesToSkyDirection(state.moonAngles[0], state.moonAngles[1]);

		// Resolve the moon's phase, illumination, and the source used for directional shadows.
		boolean useSyntheticMoonPhase = configCycle == DaylightCycle.NIGHT || configMoonBehavior.mirrorsSun;
		float[] naturalMoonPhase = getNaturalMoonPhase(currentMillis, sunAnglesOverride, state.sunDirection, state.moonDirection);
		float naturalMoonIllumination = naturalMoonPhase[0];
		float fromMoonIllumination = fromMoonPhase.isLocked ? fromMoonPhase.illumination : naturalMoonIllumination;
		float toMoonIllumination = toMoonPhase.isLocked ? toMoonPhase.illumination : naturalMoonIllumination;
		state.moonIllumination = mix(fromMoonIllumination, toMoonIllumination, state.configurationTransition);
		if (state.moonVisibility == 0)
			state.moonIllumination = 0;
		state.shadowAngles = state.cycleActive ?
			state.sunAngles[0] < 0 && state.moonAngles[0] > 0 && state.moonIllumination > 0
				? state.moonAngles
				: state.sunAngles :
			environmentManager.getCurrentEnvironment().getShadowAngles();
		float[] moonPhaseLightDirection = useSyntheticMoonPhase ?
			getSyntheticMoonPhaseLightDirection(naturalMoonPhase[1]) : state.sunDirection;
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

		// Resolve the remaining shared celestial state consumed by the sky shaders.
		// Approximate the Moon's visible east/west and north/south rocking over a month.
		if (moonAnglesOverride != null || configMoonBehavior.mirrorsSun) {
			state.moonLibration = vec(0, 0);
		} else {
			double days = currentMillis / (double) DAY_MS;
			state.moonLibration = vec(
				sin((float) (days / ANOMALISTIC_MONTH_DAYS) * TWO_PI) * LONGITUDE_LIBRATION_DEG * DEG_TO_RAD,
				sin((float) (days / DRACONIC_MONTH_DAYS) * TWO_PI) * LATITUDE_LIBRATION_DEG * DEG_TO_RAD
			);
		}
		state.celestialPole = configCycle == DaylightCycle.CUSTOM_BASIC
			? anglesToSkyDirection(BASIC_SUN_TILT, 0)
			: anglesToSkyDirection((float) configLatLon[0] * DEG_TO_RAD, 0);
		state.celestialRotation = (currentInstant.toEpochMilli() % DAY_MS) / (float) DAY_MS * TWO_PI;
		resolveAuroraStrength();
	}

	private static float[] interpolateAngles(float[] from, float[] to, float[] fallback, float t) {
		if (from == null)
			from = fallback;
		if (to == null)
			to = fallback;
		return vec(mix(from[0], to[0], t), from[1] + angleDiff(to[1], from[1]) * t);
	}

	private static float[] anglesToSkyDirection(float altitude, float azimuth) {
		return normalize(
			sin(azimuth) * cos(altitude),
			sin(altitude),
			cos(azimuth) * cos(altitude)
		);
	}

	private float[] getBasicSunAngles() {
		float cyclePosition = applyBasicNightDurationWarp((float) fract(customCycleElapsedDays));
		float orbitAngle = cyclePosition * TWO_PI;
		return vec(
			asin(sin(orbitAngle) * cos(BASIC_SUN_TILT)),
			atan(cos(orbitAngle), -sin(orbitAngle) * sin(BASIC_SUN_TILT))
		);
	}

	private float[] getMovingMoonAngles(long millis, float[] sunAngles) {
		return configMoonBehavior.mirrorsSun ? vec(-sunAngles[0], sunAngles[1] + PI) :
			configCycle == DaylightCycle.CUSTOM_BASIC ? DEFAULT_STATIC_MOON_ANGLES :
			vec(AstronomyUtils.getMoonPosition(millis, configLatLon));
	}

	/** Returns illuminated fraction and orbital phase; fixed-direction lighting needs only the fraction. */
	private float[] getNaturalMoonPhase(long millis, @Nullable float[] sunOverride, float[] sunDirection, float[] moonDirection) {
		if (configCycle == DaylightCycle.CUSTOM_BASIC) {
			float phase = getBasicMoonPhase();
			return vec(.5f - .5f * cos(phase * TWO_PI), phase);
		}
		if (sunOverride == null || configCycle == DaylightCycle.NIGHT || configMoonBehavior.mirrorsSun)
			return vec(AstronomyUtils.getMoonIllumination(millis));
		// Fixed visible suns determine the phase rendered beneath them.
		return vec(saturate((1 - dot(sunDirection, moonDirection)) * .5f), 0);
	}

	private float getBasicMoonPhase() {
		return (float) fract(customCycleElapsedDays / BASIC_MOON_PHASE_PERIOD_DAYS);
	}

	/**
	 * Keep Night and mirrored moons on a fixed diagonal phase orbit around the moon.
	 */
	private float[] getSyntheticMoonPhaseLightDirection(float phase) {
		float[] moonUp = abs(state.moonDirection[1]) < .999f ? vec(0, 1, 0) : vec(0, 0, 1);
		float[] moonRight = normalize(cross(moonUp, state.moonDirection));
		moonUp = normalize(cross(state.moonDirection, moonRight));
		float[] orbitTangent = normalize(add(moonRight, multiply(moonUp, NIGHT_MOON_PHASE_TILT)));
		float phaseCos = state.moonIllumination * 2 - 1;
		float phaseSin = sqrt(max(0, 1 - phaseCos * phaseCos));
		if (sin(phase * TWO_PI) < 0)
			phaseSin = -phaseSin;
		return normalize(add(multiply(state.moonDirection, phaseCos), multiply(orbitTangent, phaseSin)));
	}

	// Sampling and shadows

	/** Sample an authored sky independently of the current area's celestial overrides and transition. */
	public void sampleLighting(SkyState.LightingSample out, SkyConfiguration sky, float[] fogColor, float minBrightness) {
		float[] sunOverride = getSunAnglesOverride(sky);
		long millis = resolveCurrentInstant(sunOverride).toEpochMilli();
		float[] sunAngles = sunOverride != null ? sunOverride : configCycle == DaylightCycle.CUSTOM_BASIC ?
			getBasicSunAngles() : vec(AstronomyUtils.getSunAngles(millis, configLatLon));
		float[] moonAngles = getMoonAnglesOverride(sky);
		if (moonAngles == null)
			moonAngles = getMovingMoonAngles(millis, sunAngles);
		MoonPhase phase = sky.forceMoonPhase != null ? sky.forceMoonPhase : configMoonPhase;
		float illumination = phase.illumination;
		if (!phase.isLocked) {
			illumination = getNaturalMoonPhase(millis, sunOverride,
				anglesToSkyDirection(sunAngles[0], sunAngles[1]), anglesToSkyDirection(moonAngles[0], moonAngles[1]))[0];
		}
		sky.evaluateGradient(out, sunAngles[0] * RAD_TO_DEG, fogColor, minBrightness);
		out.horizonLinear = ColorUtils.srgbToLinear(out.horizonSrgb);
		out.referenceFogColorLinear = fogColor;
		out.sunAltitudeDegrees = sunAngles[0] * RAD_TO_DEG;
		out.moonAltitudeDegrees = moonAngles[0] * RAD_TO_DEG;
		out.visibleMoonIllumination = isMoonHidden(sky) ? 0 : illumination * sky.moonVisibility;
	}

	/**
	 * Update directional shadows only after a perceptible angle change.
	 */
	public void updateDirectionalCamera(Camera directionalCamera) {
		float[] angles = state.shadowAngles;
		float[] orientation = { PI - angles[1], angles[0] };
		if (!state.cycleActive) {
			directionalCamera.setOrientation(orientation);
			return;
		}
		float diff = max(abs(angleDiff(orientation, directionalCamera.getOrientation())));
		float cycleDuration = sunAnglesOverride != null || configCycle.usesDefaultCycleTime ?
			SYNCED_DAYS_PERIOD_MS / (float) (60 * 1000) :
			configCycle == DaylightCycle.REAL_TIME ? 24 * 60 : configCycleDuration;
		if (diff >= DIRECTIONAL_ANGLE_UPDATE_THRESHOLD * saturate(cycleDuration / 300f))
			directionalCamera.setOrientation(orientation);
	}

	// Light schedules

	private void resolveLightScheduleState() {
		scheduleSunAltitude = state.sunAltitudeDegrees;
		sunDescending = Float.isNaN(previousScheduleSunAltitude) || scheduleSunAltitude <= previousScheduleSunAltitude;
		previousScheduleSunAltitude = scheduleSunAltitude;
		// Change offsets at solar noon, outside every dusk-to-dawn schedule. Basic starts at sunrise.
		long scheduleOffset = configCycle == DaylightCycle.CUSTOM_BASIC ? DAY_MS / 4 : DAY_MS / 2;
		scheduleNightIndex = Math.floorDiv(currentInstant.toEpochMilli() - scheduleOffset, DAY_MS);
		if (state.cycleActive)
			nightFactor = smoothstep(5, -18, scheduleSunAltitude);
	}

	public void prepareLightSchedule(Light light) {
		float activation = 1;
		if (light.def.schedule != null) {
			activation = 0;
			if (!isCycleDisabled()) {
				float randomOffset = (getScheduleRandomOffset(light) * 2 - 1) * light.def.schedule.randomOffset;
				activation = light.def.schedule.getActivation(scheduleSunAltitude, sunDescending, randomOffset);
			}
			if (activation < .001f)
				light.visible = false;
		}

		light.daylightCycleStrengthScale = activation;
		light.daylightCycleRadiusScale = activation;
		if (!state.cycleActive)
			return;

		float multiplier = light.def.nightMultiplier;
		light.daylightCycleStrengthScale *= mix(1, multiplier, nightFactor);
		if (multiplier <= 0) {
			light.daylightCycleRadiusScale = light.def.schedule != null ? 0 : mix(1, 0, nightFactor);
		} else {
			// Radius uses a smaller boost than strength to limit how far night lighting spreads.
			light.daylightCycleRadiusScale *= mix(1, multiplier, nightFactor * NIGHT_RADIUS_BOOST_FRACTION);
		}
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

	// Auroras

	private void resolveAuroraStrength() {
		double cycleTime;
		float eventStart;
		float sunAltitude = state.sunAngles[0];
		if (configCycle != DaylightCycle.CUSTOM_BASIC) {
			cycleTime = currentInstant.toEpochMilli() / (double) DAY_MS;
			eventStart = ASTRONOMICAL_NIGHT_START;
			if (configCycle.skyPreset != null)
				sunAltitude = (float) AstronomyUtils.getSunAngles(currentInstant.toEpochMilli(), configLatLon)[0];
		} else {
			cycleTime = customCycleElapsedDays;
			eventStart = 1 - configNightFraction;
		}
		// The sky shader supplies the near-horizon fade; skip when the sun is above the horizon.
		state.auroraStrength = sunAltitude < 0 ? getAuroraEventStrength(cycleTime, eventStart) : 0;
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

	private static float getAuroraEventRoll(long eventIndex, long salt) {
		long h = SEED + eventIndex * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		return (h >>> 40) * (1f / (1 << 24));
	}
}
