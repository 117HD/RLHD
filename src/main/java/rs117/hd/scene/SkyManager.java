package rs117.hd.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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
import rs117.hd.utils.collections.Util;

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
	public static final float[] DEFAULT_LATLON = { 52.2347902f, .1407562f }; // Jagex's offices, Cambridge

	private static final String DEFAULT_PRESET_NAME = "GIELINOR";
	private static final ResourcePath SKY_PRESETS_PATH = Props
		.getFile("rlhd.sky-presets-path", () -> path(SkyConfiguration.class, "sky_presets.json"));

	private static final long SECOND_MS = 1000;
	private static final long MINUTE_MS = 60 * SECOND_MS;
	private static final long HOUR_MS = 60 * MINUTE_MS;
	private static final long DAY_MS = 24 * HOUR_MS;

	// Used by the Static moon behavior when an environment provides no moon position.
	private static final float[] DEFAULT_STATIC_MOON_ANGLES = HDUtils.sunAngles(25, 73);
	private static final float[] NO_MOON_LIBRATION = { 0, 0 };

	private static final float SYNTHETIC_MOON_PERIOD_DAYS = 29.53059f;
	private static final float ANOMALISTIC_MONTH_DAYS = 27.55455f;
	private static final float DRACONIC_MONTH_DAYS = 27.21222f;

	private static final float LONGITUDE_LIBRATION_DEG = 7.9f;
	private static final float LATITUDE_LIBRATION_DEG = 6.7f;
	private static final float NIGHT_MOON_PHASE_TILT = -.35f;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	private FileWatcher.UnregisterCallback fileWatcher;

	private DaylightCycle configCycle;
	private MoonPhase configMoonPhase;
	private MoonBehavior configMoonBehavior;
	private float configCycleDuration;
	private final float[] configLatLon = new float[2];

	private long frameUtcMillis;
	private long lastDirectionalCameraUpdateMillis;

	private double customCycleElapsedDays = .35f;
	private long customCycleStartMillis;

	private boolean isSunDescending;
	private long scheduleNightIndex;
	private float nightFactor = 1;

	@Getter
	private final SkyState state = new SkyState();
	private long transitionId;
	private ResolvedEndpoint interruptedFrom;
	private float[] interruptedPole;
	private float interruptedRotation;
	private float interruptedAurora;

	private final Random random = new Random(SEED);

	@RequiredArgsConstructor
	private static final class ResolvedMoon {
		private final float[] angles;
		private final float[] illuminationDirection;
		private final float illumination;
		private final float lightIllumination;
		private final float visibility;
		private final float directionalStrength;
	}

	@RequiredArgsConstructor
	private static final class ResolvedEndpoint {
		private final float[] sunAngles;
		private final ResolvedMoon moon;
		private final float[] moonLibration;
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
		state.cycleActive = false;
		interruptedFrom = null;
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
		configMoonPhase = configMoonBehavior == MoonBehavior.DISABLED ? MoonPhase.FIRST_QUARTER : config.moonPhase();
		configCycleDuration = max(1e-6f, (float) config.customCycleDurationMinutes());

		String latLonString = config.preciseLatLon();
		float[] latLon = HDUtils.parseLatLon(latLonString);
		if (latLon == null) {
			if (!latLonString.isEmpty())
				log.warn("Ignoring invalid latitude & longitude coordinates: {}", latLon);
			latLon = vec(config.latitudeDegrees(), config.longitudeDegrees());
		}
		copyTo(configLatLon, latLon);
	}

	private static float degreesAndArcminutes(int degrees, int arcminutes, int maxDegrees) {
		float magnitude = min(abs(degrees), maxDegrees) + min(abs(arcminutes), 59) / 60.f;
		boolean negative = degrees < 0 || degrees == 0 && arcminutes < 0;
		return clamp(negative ? -magnitude : magnitude, -maxDegrees, maxDegrees);
	}

	public boolean isCycleDisabled() {
		return configCycle == DaylightCycle.OFF;
	}

	public void update() {
		frameUtcMillis = System.currentTimeMillis();

		if (isCycleDisabled()) {
			state.cycle = configCycle;
			state.utcMillis = frameUtcMillis;
			state.latLon[0] = DEFAULT_LATLON[0];
			state.latLon[1] = DEFAULT_LATLON[1];
			state.cycleActive = false;
			state.shadowAngles = environmentManager.getCurrentEnvironment().getShadowAngles();
			state.auroraStrength = 0;
			return;
		}

		if (configCycle.usesCustomCycleTime) {
			if (customCycleStartMillis == 0)
				customCycleStartMillis = Instant.ofEpochMilli(frameUtcMillis).truncatedTo(ChronoUnit.DAYS).toEpochMilli();
			customCycleElapsedDays += plugin.deltaTimeMs / (configCycleDuration * MINUTE_MS);
		}

		resolvePrimarySkyState();
		resolveLightScheduleState();
	}

	private void resolvePrimarySkyState() {
		Environment from = environmentManager.getFromEnvironment();
		Environment to = environmentManager.getToEnvironment();
		if (transitionId != environmentManager.getTransitionId()) {
			transitionId = environmentManager.getTransitionId();
			interruptedFrom = null;
			if (state.cycleActive && state.transitionProgress < 1 && environmentManager.getTransitionProgress() < 1) {
				// Continue from the last displayed result when an unfinished transition is replaced.
				interruptedFrom = new ResolvedEndpoint(
					copy(state.sunAngles),
					new ResolvedMoon(
						copy(state.moonAngles),
						copy(state.moonIlluminationDirection),
						state.moonIllumination,
						state.moonLightIllumination,
						state.moonVisibility,
						state.moonDirectionalStrength
					),
					copy(state.moonLibration)
				);
				interruptedPole = copy(state.celestialPole);
				interruptedRotation = state.celestialRotation;
				interruptedAurora = state.auroraStrength;
			}
		}
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
		boolean fixedSunAngles =
			toSky.sunAngles != null ||
			configCycle.skyPreset != null && PRESETS.containsKey(configCycle.skyPreset);
		DaylightCycle cycle = fixedSunAngles ? DaylightCycle.DEFAULT : configCycle;
		long utcMillis = resolveCurrentUtcMillis(cycle);
		out.latLon[0] = cycle.usesConfiguredCoordinates ? configLatLon[0] : DEFAULT_LATLON[0];
		out.latLon[1] = cycle.usesConfiguredCoordinates ? configLatLon[1] : DEFAULT_LATLON[1];
		ResolvedEndpoint toEndpoint = resolveEndpoint(to, cycle, utcMillis, out.latLon);
		boolean interrupted = out == state && interruptedFrom != null && t < 1;
		ResolvedEndpoint fromEndpoint = toEndpoint;
		if (interrupted)
			fromEndpoint = interruptedFrom;
		else if (t < 1 && (fromSky != toSky || from.directionalStrength != to.directionalStrength))
			fromEndpoint = resolveEndpoint(from, cycle, utcMillis, out.latLon);
		out.cycleActive = !isCycleDisabled() && allowsCycle;
		out.transitionId = transitionId;
		out.fromEnvironment = from;
		out.toEnvironment = to;
		out.transitionProgress = t;

		out.cycle = cycle;
		out.utcMillis = utcMillis;

		// Resolve and blend celestial positions, moon lighting, and shadow direction.
		out.sunAngles = interpolateAngles(fromEndpoint.sunAngles, toEndpoint.sunAngles, t);
		out.sunAltitudeDegrees = out.sunAngles[0] * RAD_TO_DEG;
		out.sunDirection = anglesToSkyDirection(out.sunAngles[0], out.sunAngles[1]);

		ResolvedMoon fromMoon = fromEndpoint.moon;
		ResolvedMoon toMoon = toEndpoint.moon;
		out.moonAngles = interpolateAngles(fromMoon.angles, toMoon.angles, t);
		out.moonAltitudeDegrees = out.moonAngles[0] * RAD_TO_DEG;
		out.moonDirection = anglesToSkyDirection(out.moonAngles[0], out.moonAngles[1]);

		out.moonVisibility = mix(fromMoon.visibility, toMoon.visibility, t);
		out.moonDirectionalStrength = mix(fromMoon.directionalStrength, toMoon.directionalStrength, t);
		out.moonIllumination = mix(fromMoon.illumination, toMoon.illumination, t);
		out.moonLightIllumination = mix(fromMoon.lightIllumination, toMoon.lightIllumination, t);
		out.moonIlluminationDirection = interpolateDirection(fromMoon.illuminationDirection, toMoon.illuminationDirection, t);
		out.shadowAngles = out.cycleActive ? out.sunAngles : fallbackShadowAngles;

		// Resolve the remaining shared celestial state consumed by the sky shaders.
		// Approximate the Moon's visible east/west and north/south rocking over a month.
		mix(out.moonLibration, fromEndpoint.moonLibration, toEndpoint.moonLibration, t);
		out.celestialPole = anglesToSkyDirection(out.latLon[0] * DEG_TO_RAD, 0);
		out.celestialRotation = (utcMillis % DAY_MS) / (float) DAY_MS * TWO_PI;
		resolveAuroraStrength(out);
		if (interrupted) {
			SkyState target = new SkyState();
			resolveSkyState(target, to, to, 1, allowsCycle, fallbackShadowAngles);
			out.celestialPole = interpolateDirection(interruptedPole, target.celestialPole, t);
			out.celestialRotation = interruptedRotation + angleDiff(target.celestialRotation, interruptedRotation) * t;
			out.auroraStrength = mix(interruptedAurora, target.auroraStrength, t);
		}
	}

	private ResolvedEndpoint resolveEndpoint(Environment environment, DaylightCycle cycle, long utcMillis, float[] latLon) {
		SkyConfiguration sky = environment.getSky();
		float[] sunAngles = sky.sunAngles;
		if (sunAngles == null && configCycle.skyPreset != null) {
			var preset = PRESETS.get(configCycle.skyPreset);
			if (preset != null)
				sunAngles = preset.sunAngles;
		}
		boolean fixedSunAngles = sunAngles != null;
		if (!fixedSunAngles)
			sunAngles = vec(AstronomyUtils.getSunAngles(utcMillis, latLon));

		ResolvedMoon moon = resolveMoon(sky, utcMillis, sunAngles, fixedSunAngles, latLon);
		float[] moonLibration = NO_MOON_LIBRATION;
		if (sky.moonAngles == null &&
			configMoonBehavior != MoonBehavior.STATIC &&
			configMoonBehavior != MoonBehavior.MIRRORED
		) {
			double days = utcMillis / (double) DAY_MS;
			moonLibration = vec(
				sin((float) (days / ANOMALISTIC_MONTH_DAYS) * TWO_PI) * LONGITUDE_LIBRATION_DEG * DEG_TO_RAD,
				sin((float) (days / DRACONIC_MONTH_DAYS) * TWO_PI) * LATITUDE_LIBRATION_DEG * DEG_TO_RAD
			);
		}
		return new ResolvedEndpoint(sunAngles, moon, moonLibration);
	}

	private long resolveCurrentUtcMillis(DaylightCycle cycle) {
		if (cycle.usesDefaultCycleTime)
			return frameUtcMillis * DAY_MS / HOUR_MS; // One simulated day per real hour

		switch (cycle) {
			case OFF:
			case REAL_TIME:
				return frameUtcMillis;
			case CUSTOM_REALISTIC:
				float timeOfDay = (float) fract(customCycleElapsedDays);
				return customCycleStartMillis + floor(customCycleElapsedDays) * DAY_MS + (long) (timeOfDay * DAY_MS);
		}

		throw new IllegalStateException("Unhandled daylight cycle mode: " + cycle);
	}

	private static float[] interpolateDirection(float[] from, float[] to, float t) {
		float cosine = clamp(dot(from, to), -1, 1);
		if (cosine > .9999f)
			return normalize(mix(from, to, t));
		float[] tangent = subtract(to, multiply(from, cosine));
		if (dot(tangent, tangent) < 1e-8f)
			// Opposite directions need an arbitrary, stable rotation plane.
			tangent = cross(from, abs(from[1]) < .999f ? vec(0, 1, 0) : vec(1, 0, 0));
		float angle = acos(cosine) * t;
		return normalize(add(multiply(from, cos(angle)), multiply(normalize(tangent), sin(angle))));
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
		long millis,
		float[] sunAngles,
		boolean fixedSunAngles,
		float[] latLon
	) {
		float[] angles;
		if (sky.moonAngles != null) {
			angles = sky.moonAngles;
		} else if (configMoonBehavior == MoonBehavior.STATIC) {
			angles = DEFAULT_STATIC_MOON_ANGLES;
		} else if (configMoonBehavior == MoonBehavior.MIRRORED) {
			angles = vec(-sunAngles[0], sunAngles[1] + PI);
		} else {
			angles = AstronomyUtils.getMoonPosition(millis, latLon);
		}
		float[] moonDirection = anglesToSkyDirection(angles[0], angles[1]);
		float[] sunDirection = anglesToSkyDirection(sunAngles[0], sunAngles[1]);
		MoonPhase phase = sky.forceMoonPhase != null ? sky.forceMoonPhase : configMoonPhase;
		float illumination, orbit;
		if (configMoonBehavior == MoonBehavior.MIRRORED) {
			// A mirrored moon needs an independent phase, since the sun is always opposite it.
			orbit = fract(millis / (DAY_MS * SYNTHETIC_MOON_PERIOD_DAYS));
			illumination = .5f - .5f * cos(orbit * TWO_PI);
		} else if (!fixedSunAngles || configCycle == DaylightCycle.NIGHT) {
			float[] astronomy = AstronomyUtils.getMoonIllumination(millis);
			illumination = astronomy[0];
			orbit = astronomy[1];
		} else {
			// Fixed visible suns determine the phase rendered beneath them.
			illumination = saturate((1 - dot(sunDirection, moonDirection)) * .5f);
			orbit = 0;
		}
		if (phase.isLocked)
			illumination = phase.illumination;

		float[] illuminationDirection = configCycle == DaylightCycle.NIGHT || configMoonBehavior == MoonBehavior.MIRRORED ?
			resolveSyntheticMoonIlluminationDirection(moonDirection, illumination, orbit) : sunDirection;
		if (phase.reversesTerminator) {
			// Preserve the radial component while moving the illuminated side across the disk.
			float radial = dot(illuminationDirection, moonDirection);
			illuminationDirection = subtract(multiply(moonDirection, 2 * radial), illuminationDirection);
		}

		boolean naturalMoonlightEnabled =
			!sky.hideMoon ||
			sky.moonLightVisibility >= 0 ||
			sky.moonDirectionalStrength >= 0 ||
			sky.moonAmbientStrength >= 0;
		float moonLightVisibility = sky.moonLightVisibility < 0 ? 1 : sky.moonLightVisibility;
		float lightIllumination = moonLightVisibility * max(sky.minMoonIllumination, naturalMoonlightEnabled ? illumination : 0);
		float visibility = sky.moonVisibility;
		if (sky.hideMoon || configMoonBehavior == MoonBehavior.DISABLED && sky.forceMoonPhase == null)
			visibility = 0;
		return new ResolvedMoon(
			angles,
			illuminationDirection,
			illumination,
			lightIllumination,
			visibility,
			sky.moonDirectionalStrength
		);
	}

	/**
	 * Keep Night and mirrored moons on a fixed diagonal phase orbit around the moon.
	 */
	private static float[] resolveSyntheticMoonIlluminationDirection(float[] moonDirection, float illumination, float orbit) {
		float[] moonUp = abs(moonDirection[1]) < .999f ? vec(0, 1, 0) : vec(0, 0, 1);
		float[] moonRight = normalize(cross(moonUp, moonDirection));
		moonUp = normalize(cross(moonDirection, moonRight));
		float[] orbitTangent = normalize(add(moonRight, multiply(moonUp, NIGHT_MOON_PHASE_TILT)));
		float phaseCos = illumination * 2 - 1;
		float phaseSin = sqrt(max(0, 1 - phaseCos * phaseCos));
		if (sin(orbit * TWO_PI) < 0)
			phaseSin = -phaseSin;
		return normalize(add(multiply(moonDirection, phaseCos), multiply(orbitTangent, phaseSin)));
	}

	/**
	 * Resolve an authored sky independently of the current area's overrides and transition.
	 * The reusable sample contains both its complete celestial state and evaluated gradient.
	 */
	public void sampleLighting(SkyState.LightingSample out, Environment environment, float[] fogColor) {
		resolveSkyState(out.sky, environment, environment, 1, true, environment.getShadowAngles());
		environment.getSky().evaluateGradient(out, out.sky.sunAltitudeDegrees, fogColor);
		out.referenceFogColorLinear = fogColor;
	}

	public void updateDirectionalCamera(Camera directionalCamera, boolean useMoon) {
		float[] angles = useMoon ? state.moonAngles : state.shadowAngles;
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
		// Use the orbit's local slope, independent of config changes and environment transitions.
		long millis = state.utcMillis;
		isSunDescending =
			AstronomyUtils.getSunAngles(millis + 1000, state.latLon)[0] <=
			AstronomyUtils.getSunAngles(millis - 1000, state.latLon)[0];
		// Change offsets at solar noon, outside every dusk-to-dawn schedule. Basic starts at sunrise.
		scheduleNightIndex = Math.floorDiv(state.utcMillis - DAY_MS / 2, DAY_MS);
		if (state.cycleActive)
			nightFactor = smoothstep(5, -18, state.sunAltitudeDegrees);
	}

	public void prepareLightSchedule(Light light) {
		float activation = 1;
		if (light.def.schedule != null) {
			activation = 0;
			if (!isCycleDisabled()) {
				int h = Float.floatToIntBits(light.pos[0]);
				h = 31 * h + Float.floatToIntBits(light.pos[1]);
				h = 31 * h + Float.floatToIntBits(light.pos[2]);
				h = 31 * h + light.plane;
				h = 31 * h + Long.hashCode(scheduleNightIndex);
				random.setSeed(h);

				float randomOffset = (random.nextFloat() * 2 - 1) * light.def.schedule.randomOffset;
				activation = light.def.schedule.getActivation(state.sunAltitudeDegrees, isSunDescending, randomOffset);
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
			light.daylightCycleRadiusScale *= mix(1, multiplier, nightFactor * .25f);
		}
	}

	private void resolveAuroraStrength(SkyState state) {
		double elapsedDays;
		float sunAltitude = state.sunAngles[0];
		// Longitude shifts UTC to local solar time, with midnight at each integer day.
		elapsedDays = state.utcMillis / (double) DAY_MS + state.latLon[1] / 360;
		if (configCycle.skyPreset != null)
			sunAltitude = AstronomyUtils.getSunAngles(state.utcMillis, state.latLon)[0];
		// Faint auroras disappear through twilight; the event itself continues while invisible.
		float darkness = smoothstep(-6 * DEG_TO_RAD, -18 * DEG_TO_RAD, sunAltitude);
		state.auroraStrength = state.cycleActive ? getAuroraEventStrength(elapsedDays) * darkness : 0;
	}

	private float getAuroraEventStrength(double elapsedDays) {
		// One event per 24 simulated days on average. Timing is in simulated hours.
		final float chance = 1 / 24.f;
		final float fadeFraction = .2f;
		long day = (long) Math.floor(elapsedDays);
		float strength = 0;
		// Include neighboring midnights so events can start before or continue after midnight.
		// The onset and duration bounds ensure omitted events have already faded out.
		for (long eventDay = day - 1; eventDay <= day + 1; eventDay++) {
			// Hash adjacent days to avoid correlated first outputs from Random.
			random.setSeed(Util.murmurHash3(SEED ^ eventDay));
			if (random.nextFloat() >= chance)
				continue;

			float onset = clamp((float) random.nextGaussian() * 1.2f, -6, 6);
			float duration = clamp(80 + (float) random.nextGaussian() * 40, 10, 180) / 60;
			float elapsed = (float) ((elapsedDays - eventDay) * 24) - onset;
			if (elapsed < 0 || elapsed >= duration)
				continue;

			float fade = duration * fadeFraction;
			float eventStrength =
				smoothstep(0, fade, elapsed) *
				(1 - smoothstep(duration - fade, duration, elapsed));
			strength += (1 - strength) * eventStrength;
		}
		return strength;
	}
}
