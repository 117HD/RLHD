package mygame.renderer;

import lombok.extern.slf4j.Slf4j;
import mygame.environment.EnvironmentManager;
import mygame.lighting.LightManager;
import mygame.utils.Timer;
//import rs117.hd.EnvironmentManager;
//import rs117.hd.LightManager;
//import rs117.hd.Timer;
//import rs117.hd.scene.SceneContext;



@Slf4j
public class Camera {
	private final float x, y, z;
	private final float pitch, yaw;
	private final mygame.environment.EnvironmentManager environmentManager;
	private final mygame.lighting.LightManager lightManager;
	private final mygame.utils.Timer frameTimer;

	public Camera(double x, double y, double z, double pitch, double yaw,
		EnvironmentManager environmentManager, LightManager lightManager, Timer frameTimer) {
		this.x = (float) x;
		this.y = (float) y;
		this.z = (float) z;
		this.pitch = (float) pitch;
		this.yaw = (float) yaw;
		this.environmentManager = environmentManager;
		this.lightManager = lightManager;
		this.frameTimer = frameTimer;
	}

	public float[] getPosition() {
		return new float[]{x, y, z};
	}

	public float[] getOrientation() {
		return new float[]{yaw, pitch};
	}

	/**
	 * Updates the environment and lighting settings.
	 */
	public void updateEnvironmentAndLights(SceneContext sceneContext) {
		try {
			frameTimer.begin(Timer.UPDATE_ENVIRONMENT);
			environmentManager.update(sceneContext);
			frameTimer.end(Timer.UPDATE_ENVIRONMENT);

			frameTimer.begin(Timer.UPDATE_LIGHTS);
			lightManager.update(sceneContext);
			frameTimer.end(Timer.UPDATE_LIGHTS);
		} catch (Exception ex) {
			log.error("Error while updating environment or lights:", ex);
			throw new RuntimeException("Failed to update environment and lights", ex);
		}
	}
}
