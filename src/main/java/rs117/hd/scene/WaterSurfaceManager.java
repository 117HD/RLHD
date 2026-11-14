package rs117.hd.scene;

import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;

import static rs117.hd.utils.MathUtils.*;

/**
 * Tracks the wind over open water and how rough it is, for the water shader's foam, and sends them along with the
 * boat wakes
 */
@Singleton
public class WaterSurfaceManager {
	// Seconds of wind and roughness kept, so each ocean wave can keep those it spawned with. A multiple of 4 dividing
	// 65536, where the shader time wraps.
	public static final int WIND_HISTORY_LENGTH = 32;
	// Seconds the water's wind takes to mostly turn to the environment's, more slowly than the environment blends
	private static final float WIND_EASING_SECONDS = 12;
	// The wind speed the foam's drift is tuned for, Environment's default
	private static final float DEFAULT_WIND_SPEED = 15;
	// How rough open water is, from the wind strength relative to the overworld's and the environment's waterRoughness,
	// easing in a few seconds. 1 is the overworld's.
	private static final float ROUGHNESS_PER_WIND_STRENGTH = 1 / 100f;
	private static final float ROUGHNESS_MAX = 2;
	private static final float ROUGHNESS_RISE_SECONDS = 1.5f;
	private static final float ROUGHNESS_FALL_SECONDS = 0.75f;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private BoatWakes boatWakes;

	// The water's wind angle, unset until the first update. Two chained easings, so it starts turning gently.
	private float windAngle = Float.NaN;
	private float windEasing;
	// Total wind drift along local X and Z, in seconds at the default wind speed
	private final float[] windDrift = new float[2];
	private float roughness;
	// Seconds, running faster the rougher the water
	private float churnTime;
	// The wind angle and roughness at the start of each recent second, indexed by the second
	private final float[] windHistory = new float[WIND_HISTORY_LENGTH];
	private final float[] roughnessHistory = new float[WIND_HISTORY_LENGTH];
	private int historySecond;

	/**
	 * Start afresh along with the plugin's clock, which the history and the boats' paths are timed by
	 */
	public void startUp() {
		windAngle = Float.NaN;
		boatWakes.reset();
	}

	/**
	 * Ease the water's wind and roughness towards the environment's, and advance the drift and churn with them
	 */
	public void update() {
		float deltaTime = plugin.deltaTime;
		var env = environmentManager.getCurrentEnvironment();
		boolean first = Float.isNaN(windAngle);

		// The wind turns the shortest way round
		if (first) {
			windAngle = windEasing = env.windAngle;
		} else {
			float rate = 1 - (float) Math.exp(-deltaTime / WIND_EASING_SECONDS);
			windEasing += (float) Math.IEEEremainder(env.windAngle - windEasing, 2 * Math.PI) * rate;
			windAngle += (float) Math.IEEEremainder(windEasing - windAngle, 2 * Math.PI) * rate;
		}

		{
			// From the environment being transitioned to, so the water changes quickly after the easing. Most
			// environments don't set a wind strength, so treat 0 as the overworld's.
			var to = environmentManager.getToEnvironment();
			float overworldWind = environmentManager.getOverworldEnvironment().windStrength;
			float wind = to.windStrength > 0 ? to.windStrength : overworldWind;
			float target = (1 + wind * ROUGHNESS_PER_WIND_STRENGTH) / (1 + overworldWind * ROUGHNESS_PER_WIND_STRENGTH);
			target = min(target * max(to.waterRoughness, 0), ROUGHNESS_MAX);
			if (first) {
				roughness = target;
			} else {
				float easing = target > roughness ? ROUGHNESS_RISE_SECONDS : ROUGHNESS_FALL_SECONDS;
				roughness += (target - roughness) * (1 - (float) Math.exp(-deltaTime / easing));
			}
		}

		// The churn runs that much faster than real time
		churnTime = (churnTime + deltaTime * roughness) % MAX_FLOAT_WITH_128TH_PRECISION;
		float driftStep = deltaTime * env.windSpeed / DEFAULT_WIND_SPEED;
		windDrift[0] = (float) ((windDrift[0] + driftStep * Math.cos(windAngle)) % MAX_FLOAT_WITH_128TH_PRECISION);
		windDrift[1] = (float) ((windDrift[1] + driftStep * Math.sin(windAngle)) % MAX_FLOAT_WITH_128TH_PRECISION);

		// Record the wind and roughness at the start of each second, filling the whole history the first time
		int second = (int) Math.floor(plugin.elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION);
		if (first) {
			Arrays.fill(windHistory, windAngle);
			Arrays.fill(roughnessHistory, roughness);
		} else if (second != historySecond) {
			windHistory[second % WIND_HISTORY_LENGTH] = windAngle;
			roughnessHistory[second % WIND_HISTORY_LENGTH] = roughness;
		}
		historySecond = second;
	}

	/**
	 * Fill in the water's wind, roughness and boat wake uniforms
	 *
	 * @param drawnScene the scene being drawn, which can lag behind the client's across loading lines
	 */
	public void upload(Scene drawnScene) {
		// The first frame is drawn before the first update
		if (Float.isNaN(windAngle))
			update();

		var ubo = plugin.uboGlobal;
		ubo.windDirection.set((float) Math.cos(windAngle), (float) Math.sin(windAngle));
		ubo.windDrift.set(windDrift);
		ubo.waterRoughness.set(roughness);
		ubo.waterChurnTime.set(churnTime);
		for (int i = 0; i < ubo.windHistory.length; i++) {
			int j = i * 4;
			ubo.windHistory[i].set(windHistory[j], windHistory[j + 1], windHistory[j + 2], windHistory[j + 3]);
			ubo.roughnessHistory[i].set(
				roughnessHistory[j], roughnessHistory[j + 1], roughnessHistory[j + 2], roughnessHistory[j + 3]);
		}
		if (config.boatWakes())
			boatWakes.update(client, drawnScene, ubo, (float) plugin.elapsedTime);
	}
}
