package rs117.hd.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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
	private static final double SYNTHETIC_MOON_PERIOD_DAYS = 29.53059;

	// Used by the Static moon behavior when an environment provides no moon position.
	private static final float[] DEFAULT_STATIC_MOON_ANGLES = HDUtils.sunAngles(15, 30);

	private static final double ANOMALISTIC_MONTH_DAYS = 27.55455;
	private static final double DRACONIC_MONTH_DAYS = 27.21222;
	private static final float LONGITUDE_LIBRATION_DEG = 7.9f;
	private static final float LATITUDE_LIBRATION_DEG = 6.7f;
	private static final float NIGHT_MOON_PHASE_TILT = -.35f;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EnvironmentManager environmentManager;

	private FileWatcher.UnregisterCallback fileWatcher;

	private DaylightCycle configCycle;
	private float configNightFraction;
	private MoonPhase configMoonPhase;
	private MoonBehavior configMoonBehavior;
	private float configCycleDuration;
	private final double[] configLatLon = new double[2];

	private long frameUtcMillis;
	private long lastDirectionalCameraUpdateMillis;

	private double customCycleElapsedDays = .35;
	private Instant customCycleStart;

	private float scheduleSunAltitude;
	private boolean sunDescending;
	private long scheduleNightIndex;
	private float nightFactor = 1;

	@Getter
	private final SkyState state = new SkyState();

	@RequiredArgsConstructor
	private static final class ResolvedMoon {
		private final float[] angles;
		private final float illumination;
		private final float lightIllumination;
		private final float visibility;
		private final float directionalStrength;
		private final MoonPhase phase;
		private final float orbit;
		private final boolean usesSyntheticLightDirection;
	}

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
		configMoonPhase = configMoonBehavior.isDisabled ? MoonPhase.FIRST_QUARTER : config.moonPhase();
		configCycleDuration = max(1e-6f, (float) config.customCycleDurationMinutes());
		configNightFraction = clamp(config.basicNightPercentage(), 0, 100) / 100f;

		if (configCycle == DaylightCycle.REAL_TIME || configCycle == DaylightCycle.CUSTOM_REALISTIC) {
			configLatLon[0] = degreesAndArcminutes(config.latitudeDegrees(), config.latitudeArcminutes(), 90);
			configLatLon[1] = degreesAndArcminutes(config.longitudeDegrees(), config.longitudeArcminutes(), 180);
		} else {
			configLatLon[0] = DEFAULT_LATLON[0];
			configLatLon[1] = DEFAULT_LATLON[1];
		}
	}

	private static double degreesAndArcminutes(int degrees, int arcminutes, int maxDegrees) {
		double magnitude = min(abs(degrees), maxDegrees) + min(abs(arcminutes), 59) / 60.0;
		boolean negative = degrees < 0 || degrees == 0 && arcminutes < 0;
		return clamp(negative ? -magnitude : magnitude, -maxDegrees, maxDegrees);
	}

	public boolean isCycleDisabled() {
		return configCycle == DaylightCycle.OFF;
	}

	public void update() {
		frameUtcMillis = System.currentTimeMillis();
		if (customCycleStart == null)
			customCycleStart = Instant.ofEpochMilli(frameUtcMillis).truncatedTo(ChronoUnit.DAYS);
		if (isCycleDisabled()) {
			state.cycle = configCycle;
			state.utcMillis = frameUtcMillis;
			state.cycleActive = false;
			state.shadowAngles = environmentManager.getCurrentEnvironment().getShadowAngles();
			state.auroraStrength = 0;
			return;
		}

		if (configCycle.usesCustomCycleTime)
			customCycleElapsedDays += plugin.deltaTimeMs / (configCycleDuration * 60.0 * 1000);

		resolveSkyState();
		resolveLightScheduleState();
	}

	private float[] getSunAngles(SkyConfiguration sky, long millis) {
		float[] angles = sky.sunAngles;
		if (angles == null && configCycle.skyPreset != null) {
			var preset = PRESETS.get(configCycle.skyPreset);
			if (preset != null)
				angles = preset.sunAngles;
		}
		if (angles != null)
			return angles;
		if (configCycle == DaylightCycle.CUSTOM_BASIC) {
			float orbitAngle = (millis % DAY_MS) / (float) DAY_MS * TWO_PI;
			return vec(
				asin(sin(orbitAngle) * cos(BASIC_SUN_TILT)),
				atan(cos(orbitAngle), -sin(orbitAngle) * sin(BASIC_SUN_TILT))
			);
		}
		return vec(AstronomyUtils.getSunAngles(millis, getCoordinates(resolveCycle(sky))));
	}

	private DaylightCycle resolveCycle(SkyConfiguration sky) {
		return hasFixedSunAngles(sky) ? DaylightCycle.DEFAULT : configCycle;
	}

	private boolean hasFixedSunAngles(SkyConfiguration sky) {
		SkyConfiguration preset = configCycle.skyPreset == null ? null : PRESETS.get(configCycle.skyPreset);
		return sky.sunAngles != null || preset != null && preset.sunAngles != null;
	}

	private double[] getCoordinates(DaylightCycle cycle) {
		return cycle == DaylightCycle.REAL_TIME || cycle == DaylightCycle.CUSTOM_REALISTIC ? configLatLon : DEFAULT_LATLON;
	}

	private boolean isMoonHidden(SkyConfiguration sky) {
		return sky.hideMoon || configMoonBehavior.isDisabled && sky.forceMoonPhase == null;
	}

	private long resolveCurrentUtcMillis(DaylightCycle cycle) {
		if (cycle.usesDefaultCycleTime)
			// One simulated day per real hour, synchronized to the Unix epoch.
			return frameUtcMillis * (DAY_MS / SYNCED_DAYS_PERIOD_MS);

		switch (cycle) {
			case OFF:
			case REAL_TIME:
				return frameUtcMillis;
			case CUSTOM_REALISTIC:
			case CUSTOM_BASIC:
				double daytime = fract(customCycleElapsedDays);
				if (cycle == DaylightCycle.CUSTOM_BASIC)
					daytime = applyBasicNightDurationWarp((float) daytime);
				return customCycleStart.toEpochMilli() + floor(customCycleElapsedDays) * DAY_MS + (long) (daytime * DAY_MS);
		}

		throw new IllegalStateException("Unhandled daylight cycle mode: " + cycle);
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

	private void resolveSkyState() {
		Environment from = environmentManager.getFromEnvironment();
		Environment to = environmentManager.getToEnvironment();
		resolveSkyState(
			state,
			from,
			to,
			environmentManager.getTransitionProgress(),
			environmentManager.getTargetEnvironment().isOverworld,
			environmentManager.getCurrentEnvironment().getShadowAngles()
		);
	}

	private void resolveSkyState(
		SkyState out,
		Environment from,
		Environment to,
		float t,
		boolean allowsCycle,
		float[] fallbackShadowAngles
	) {
		SkyConfiguration fromSky = from.getSky();
		SkyConfiguration toSky = to.getSky();
		out.cycleActive = !isCycleDisabled() && allowsCycle;
		out.fromConfiguration = fromSky;
		out.toConfiguration = toSky;
		out.configurationTransition = t;

		// Fixed environment angles use Default's slower time as a stable tuning baseline.
		DaylightCycle targetCycle = resolveCycle(toSky);
		DaylightCycle fromCycle = resolveCycle(fromSky);
		out.cycle = targetCycle;
		out.utcMillis = resolveCurrentUtcMillis(targetCycle);
		long millis = out.utcMillis;
		long fromMillis = resolveCurrentUtcMillis(fromCycle);

		// Resolve and blend celestial positions, moon lighting, and shadow direction.
		{
			float[] fromSunAngles = getSunAngles(fromSky, fromMillis);
			float[] toSunAngles = getSunAngles(toSky, millis);
			out.sunAngles = interpolateAngles(fromSunAngles, toSunAngles, t);
			out.sunAltitudeDegrees = out.sunAngles[0] * RAD_TO_DEG;
			out.sunDirection = anglesToSkyDirection(out.sunAngles[0], out.sunAngles[1]);

			ResolvedMoon fromMoon = resolveMoon(fromSky, from.directionalStrength, fromCycle, fromMillis, fromSunAngles);
			ResolvedMoon toMoon = resolveMoon(toSky, to.directionalStrength, targetCycle, millis, toSunAngles);
			out.moonAngles = interpolateAngles(fromMoon.angles, toMoon.angles, t);
			out.moonAltitudeDegrees = out.moonAngles[0] * RAD_TO_DEG;
			out.moonDirection = anglesToSkyDirection(out.moonAngles[0], out.moonAngles[1]);

			out.moonVisibility = mix(fromMoon.visibility, toMoon.visibility, t);
			out.moonDirectionalStrength = mix(fromMoon.directionalStrength, toMoon.directionalStrength, t);
			out.moonIllumination = mix(fromMoon.illumination, toMoon.illumination, t);
			out.moonLightIllumination = mix(fromMoon.lightIllumination, toMoon.lightIllumination, t);
			out.shadowAngles = out.cycleActive ?
				out.sunAngles[0] < 0 && out.moonAngles[0] > 0 && out.moonLightIllumination > 0
					? out.moonAngles
					: out.sunAngles :
				fallbackShadowAngles == null ? out.sunAngles : fallbackShadowAngles;

			float phaseOrbit = fromMoon.orbit + angleDiff(toMoon.orbit * TWO_PI, fromMoon.orbit * TWO_PI) / TWO_PI * t;
			out.moonPhaseLightDirection = toMoon.usesSyntheticLightDirection ?
				getSyntheticMoonPhaseLightDirection(out, phaseOrbit) : out.sunDirection;
			out.moonPhaseReversed = t == 0 && fromMoon.phase.reversesTerminator || t == 1 && toMoon.phase.reversesTerminator;
			if (t > 0 && t < 1) {
				float fromSign = fromMoon.phase.reversesTerminator ? -1 : 1;
				float[] moonPhaseLightDirection = out.moonPhaseLightDirection;
				out.moonPhaseLightDirection = multiply(moonPhaseLightDirection, fromSign);
				if (fromMoon.phase.reversesTerminator != toMoon.phase.reversesTerminator) {
					// Rotate between waxing and waning; a linear blend would pass through a zero direction.
					float[] phaseTangent = cross(out.moonDirection, moonPhaseLightDirection);
					if (dot(phaseTangent, phaseTangent) < 1e-6f) {
						phaseTangent = cross(
							out.moonDirection,
							abs(out.moonDirection[1]) < .999f ? vec(0, 1, 0) : vec(0, 0, 1)
						);
					}
					out.moonPhaseLightDirection = normalize(add(
						multiply(out.moonPhaseLightDirection, cos(PI * t)),
						multiply(normalize(phaseTangent), sin(PI * t))
					));
				}
			}
		}

		// Resolve the remaining shared celestial state consumed by the sky shaders.
		// Approximate the Moon's visible east/west and north/south rocking over a month.
		if (toSky.moonAngles != null || configMoonBehavior.isStatic || configMoonBehavior.mirrorsSun) {
			out.moonLibration = vec(0, 0);
		} else {
			double days = (fromMillis + (millis - fromMillis) * (double) t) / DAY_MS;
			out.moonLibration = vec(
				sin((float) (days / ANOMALISTIC_MONTH_DAYS) * TWO_PI) * LONGITUDE_LIBRATION_DEG * DEG_TO_RAD,
				sin((float) (days / DRACONIC_MONTH_DAYS) * TWO_PI) * LATITUDE_LIBRATION_DEG * DEG_TO_RAD
			);
		}
		float fromPole = fromCycle == DaylightCycle.CUSTOM_BASIC ? BASIC_SUN_TILT : (float) getCoordinates(fromCycle)[0] * DEG_TO_RAD;
		float toPole = targetCycle == DaylightCycle.CUSTOM_BASIC ? BASIC_SUN_TILT : (float) getCoordinates(targetCycle)[0] * DEG_TO_RAD;
		out.celestialPole = anglesToSkyDirection(mix(fromPole, toPole, t), 0);
		float fromRotation = (fromMillis % DAY_MS) / (float) DAY_MS * TWO_PI;
		float toRotation = (millis % DAY_MS) / (float) DAY_MS * TWO_PI;
		out.celestialRotation = fromRotation + angleDiff(toRotation, fromRotation) * t;
		resolveAuroraStrength(out);
	}

	private static float[] interpolateAngles(float[] from, float[] to, float t) {
		return vec(mix(from[0], to[0], t), from[1] + angleDiff(to[1], from[1]) * t);
	}

	private static float[] anglesToSkyDirection(float altitude, float azimuth) {
		return normalize(
			sin(azimuth) * cos(altitude),
			sin(altitude),
			cos(azimuth) * cos(altitude)
		);
	}

	private ResolvedMoon resolveMoon(
		SkyConfiguration sky,
		float fallbackDirectionalStrength,
		DaylightCycle cycle,
		long millis,
		float[] sunAngles
	) {
		float[] angles = sky.moonAngles;
		if (angles == null) {
			if (configMoonBehavior.isStatic)
				angles = DEFAULT_STATIC_MOON_ANGLES;
			else if (configMoonBehavior.mirrorsSun)
				angles = vec(-sunAngles[0], sunAngles[1] + PI);
			else
				angles = vec(AstronomyUtils.getMoonPosition(millis, getCoordinates(cycle)));
		}
		boolean usesSyntheticLightDirection = configCycle == DaylightCycle.NIGHT || configMoonBehavior.mirrorsSun;
		MoonPhase phase = sky.forceMoonPhase != null ? sky.forceMoonPhase : configMoonPhase;
		float illumination;
		float orbit;
		if (configMoonBehavior.mirrorsSun) {
			// A mirrored moon needs an independent phase, since the sun is always opposite it.
			orbit = (float) fract(millis / (DAY_MS * SYNTHETIC_MOON_PERIOD_DAYS));
			illumination = .5f - .5f * cos(orbit * TWO_PI);
		} else if (!hasFixedSunAngles(sky) || usesSyntheticLightDirection) {
			double[] astronomy = AstronomyUtils.getMoonIllumination(millis);
			illumination = (float) astronomy[0];
			orbit = (float) astronomy[1];
		} else {
			// Fixed visible suns determine the phase rendered beneath them.
			float[] sunDirection = anglesToSkyDirection(sunAngles[0], sunAngles[1]);
			float[] moonDirection = anglesToSkyDirection(angles[0], angles[1]);
			illumination = saturate((1 - dot(sunDirection, moonDirection)) * .5f);
			orbit = 0;
		}
		if (phase.isLocked)
			illumination = phase.illumination;
		// Explicit moonlight settings can illuminate a scene even when its moon is hidden.
		float lightIllumination = sky.hideMoon && sky.moonLightVisibility < 0 && sky.moonDirectionalStrength < 0 ? 0 : illumination;
		lightIllumination = max(lightIllumination, sky.minMoonIllumination) *
							(sky.moonLightVisibility < 0 ? 1 : sky.moonLightVisibility);
		float visibility = isMoonHidden(sky) ? 0 : sky.moonVisibility;
		float directionalStrength = sky.moonDirectionalStrength < 0 ? fallbackDirectionalStrength : sky.moonDirectionalStrength;
		return new ResolvedMoon(
			angles,
			illumination,
			lightIllumination,
			visibility,
			directionalStrength,
			phase,
			orbit,
			usesSyntheticLightDirection
		);
	}

	/**
	 * Keep Night and mirrored moons on a fixed diagonal phase orbit around the moon.
	 */
	private float[] getSyntheticMoonPhaseLightDirection(SkyState state, float phase) {
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

	/**
	 * Resolve an authored sky independently of the current area's overrides and transition.
	 * The reusable sample contains both its complete celestial state and evaluated gradient.
	 */
	public void sampleLighting(SkyState.LightingSample out, Environment environment, float[] fogColor, float minBrightness) {
		resolveSkyState(out.sky, environment, environment, 1, true, environment.getShadowAngles());
		environment.getSky().evaluateGradient(out, out.sky.sunAltitudeDegrees, fogColor, minBrightness);
		out.referenceFogColorLinear = fogColor;
	}

	public void updateDirectionalCamera(Camera directionalCamera) {
		float[] angles = state.shadowAngles;
		float[] orientation = { PI - angles[1], angles[0] };
		if (state.cycleActive) {
			final float angleThreshold = 0.0005f;
			final float timeThresholdMs = 125;
			float angleDiff = max(abs(angleDiff(orientation, directionalCamera.getOrientation())));
			if (angleDiff < angleThreshold && frameUtcMillis - lastDirectionalCameraUpdateMillis < timeThresholdMs)
				return;
		}

		directionalCamera.setOrientation(orientation);
		lastDirectionalCameraUpdateMillis = frameUtcMillis;
	}

	private void resolveLightScheduleState() {
		scheduleSunAltitude = state.sunAltitudeDegrees;
		// Use the orbit's local slope, independent of config changes and environment transitions.
		if (state.cycle == DaylightCycle.CUSTOM_BASIC) {
			sunDescending = cos((state.utcMillis % DAY_MS) / (float) DAY_MS * TWO_PI) <= 0;
		} else {
			double[] coordinates = getCoordinates(state.cycle);
			long millis = state.utcMillis;
			sunDescending =
				AstronomyUtils.getSunAngles(millis + 1000, coordinates)[0] <=
				AstronomyUtils.getSunAngles(millis - 1000, coordinates)[0];
		}
		// Change offsets at solar noon, outside every dusk-to-dawn schedule. Basic starts at sunrise.
		long scheduleOffset = state.cycle == DaylightCycle.CUSTOM_BASIC ? DAY_MS / 4 : DAY_MS / 2;
		scheduleNightIndex = Math.floorDiv(state.utcMillis - scheduleOffset, DAY_MS);
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

	private void resolveAuroraStrength(SkyState state) {
		double cycleTime;
		float eventStart;
		float sunAltitude = state.sunAngles[0];
		if (state.cycle != DaylightCycle.CUSTOM_BASIC) {
			cycleTime = state.utcMillis / (double) DAY_MS;
			eventStart = ASTRONOMICAL_NIGHT_START;
			if (configCycle.skyPreset != null)
				sunAltitude = (float) AstronomyUtils.getSunAngles(state.utcMillis, getCoordinates(state.cycle))[0];
		} else {
			cycleTime = customCycleElapsedDays;
			eventStart = 1 - configNightFraction;
		}
		// Faint auroras disappear through twilight; the event itself continues while invisible.
		float darkness = smoothstep(-6 * DEG_TO_RAD, -18 * DEG_TO_RAD, sunAltitude);
		state.auroraStrength = state.cycleActive ? getAuroraEventStrength(cycleTime, eventStart) * darkness : 0;
	}

	private float getAuroraEventStrength(double cycleTime, float eventStart) {
		long eventIndex = (long) Math.floor(cycleTime - eventStart);
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
