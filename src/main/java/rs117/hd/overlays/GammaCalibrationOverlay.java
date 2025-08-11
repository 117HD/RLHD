package rs117.hd.overlays;

import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPluginConfig.*;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class GammaCalibrationOverlay extends ShaderOverlay<GammaCalibrationOverlay.Shader> {
	static class Shader extends ShaderOverlay.Shader {
		public Uniform1f uniCalibrationTimer = addUniform1f("calibrationTimer");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/gamma_calibration_frag.glsl"));
		}
	}

	private long brightnessChangedAt;

	public GammaCalibrationOverlay() {
		setBorderless(true);
		setCentered(true);
		setMaintainAspectRatio(true);
		setInitialSize(300, 100);
	}

	private float getTimeout() {
		final int gammaCalibrationTimeout = 3000;
		long t = System.currentTimeMillis() - brightnessChangedAt;
		return saturate(1 - (float) t / gammaCalibrationTimeout);
	}

	@Override
	public boolean isHidden() {
		return super.isHidden() || getTimeout() <= 0;
	}

	@Override
	protected void updateUniforms() {
		shader.uniCalibrationTimer.set(getTimeout());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (event.getGroup().equals(CONFIG_GROUP) && event.getKey().equals(KEY_BRIGHTNESS))
			brightnessChangedAt = System.currentTimeMillis();
	}
}
