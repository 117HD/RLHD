package rs117.hd.scene;

import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;

import static rs117.hd.utils.MathUtils.*;

/**
 * Eases the wind and roughness of open water for the foam, and uploads them with the boat wakes
 */
@Singleton
public class WaterSurfaceManager {
	// Seconds of history, so each ocean wave keeps the wind it spawned with. A multiple of 4 dividing 65536.
	public static final int WIND_HISTORY_LENGTH = 32;
	// Slower than the environment's own blending
	private static final float WIND_EASING_SECONDS = 12;
	// Environment's default, which the foam's drift is tuned for
	private static final float DEFAULT_WIND_SPEED = 15;
	// Roughness 1 is the overworld's
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

	// NaN until the first update. Two chained easings, so it starts turning gently.
	private float windAngle = Float.NaN;
	private float windEasing;
	// In seconds at the default wind speed
	private final float[] windDrift = new float[2];
	// How far the ocean waves and lace have scrolled, in tiles
	private final float[] waveScroll = new float[2];
	private float roughness;
	// Runs faster the rougher the water
	private float churnTime;
	// Indexed by second
	private final float[] windHistory = new float[WIND_HISTORY_LENGTH];
	private final float[] roughnessHistory = new float[WIND_HISTORY_LENGTH];
	private int historySecond;
	private long transitionId = -1;

	/**
	 * Reset along with the plugin's clock
	 */
	public void startUp() {
		windAngle = Float.NaN;
		boatWakes.reset();
	}

	public void update() {
		float deltaTime = plugin.deltaTime;
		var env = environmentManager.getCurrentEnvironment();
		boolean first = Float.isNaN(windAngle);
		// The waves turn to the new wind straight away when the environment changes instantly, rather than easing round
		boolean instant = false;
		if (transitionId != environmentManager.getTransitionId()) {
			transitionId = environmentManager.getTransitionId();
			instant = environmentManager.isTransitionInstant();
		}

		if (first || instant) {
			windAngle = windEasing = environmentManager.getToEnvironment().windAngle;
		} else {
			float rate = 1 - (float) Math.exp(-deltaTime / WIND_EASING_SECONDS);
			windEasing += (float) Math.IEEEremainder(env.windAngle - windEasing, 2 * Math.PI) * rate;
			windAngle += (float) Math.IEEEremainder(windEasing - windAngle, 2 * Math.PI) * rate;
		}

		{
			// Targets the environment being transitioned to. A wind strength of 0 means unset.
			var to = environmentManager.getToEnvironment();
			float overworldWind = environmentManager.getOverworldEnvironment().windStrength;
			float wind = to.windStrength > 0 ? to.windStrength : overworldWind;
			float target = (1 + wind * ROUGHNESS_PER_WIND_STRENGTH) / (1 + overworldWind * ROUGHNESS_PER_WIND_STRENGTH);
			target = min(target * max(to.waveRoughness, 0), ROUGHNESS_MAX);
			if (first) {
				roughness = target;
			} else {
				float easing = target > roughness ? ROUGHNESS_RISE_SECONDS : ROUGHNESS_FALL_SECONDS;
				roughness += (target - roughness) * (1 - (float) Math.exp(-deltaTime / easing));
			}
		}

		churnTime = (churnTime + deltaTime * roughness) % MAX_FLOAT_WITH_128TH_PRECISION;
		float driftStep = deltaTime * env.windSpeed / DEFAULT_WIND_SPEED;
		windDrift[0] = (float) ((windDrift[0] + driftStep * Math.cos(windAngle)) % MAX_FLOAT_WITH_128TH_PRECISION);
		windDrift[1] = (float) ((windDrift[1] + driftStep * Math.sin(windAngle)) % MAX_FLOAT_WITH_128TH_PRECISION);
		// Clockwise from north, along local X (east) and Z (north)
		float scrollStep = deltaTime * env.waveScrollSpeed;
		waveScroll[0] = (float) ((waveScroll[0] + scrollStep * Math.sin(env.waveScrollDirection)) % MAX_FLOAT_WITH_128TH_PRECISION);
		waveScroll[1] = (float) ((waveScroll[1] + scrollStep * Math.cos(env.waveScrollDirection)) % MAX_FLOAT_WITH_128TH_PRECISION);

		int second = (int) Math.floor(plugin.elapsedTime % MAX_FLOAT_WITH_128TH_PRECISION);
		if (first) {
			Arrays.fill(windHistory, windAngle);
			Arrays.fill(roughnessHistory, roughness);
		} else if (instant) {
			// Including the waves already going
			Arrays.fill(windHistory, windAngle);
		} else if (second != historySecond) {
			windHistory[second % WIND_HISTORY_LENGTH] = windAngle;
			roughnessHistory[second % WIND_HISTORY_LENGTH] = roughness;
		}
		historySecond = second;
	}

	/**
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
		ubo.waveScroll.set(waveScroll);
		ubo.waveRegionRepeat.set(environmentManager.getCurrentEnvironment().waveRegionRepeat ? 1 : 0);
		// The roughest any wave still going could have spawned in, so the shader can skip most cells before reading
		// the roughness each one spawned with
		float maxRoughness = 0;
		for (float r : roughnessHistory)
			maxRoughness = max(maxRoughness, r);
		ubo.waveMaxRoughness.set(maxRoughness);
		// Whether every second of the history is the same, which it is unless the wind or roughness changed recently,
		// so the shader can read the first instead of each wave's own
		boolean steady = true;
		for (int i = 1; i < WIND_HISTORY_LENGTH && steady; i++)
			steady = windHistory[i] == windHistory[0] && roughnessHistory[i] == roughnessHistory[0];
		ubo.waveHistorySteady.set(steady ? 1 : 0);
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
