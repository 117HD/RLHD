package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseWheelEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import rs117.hd.HdPlugin;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_POSITIONAL_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TERRAIN_SHADOW_MAP;

@Slf4j
@Singleton
public class ShadowMapOverlay extends ShaderOverlay<ShadowMapOverlay.Shader> implements MouseWheelListener {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
		private final UniformTexture uniTerrainShadowMap = addUniformTexture("terrainShadowMap");
		private final UniformTexture uniPositionalShadowMap = addUniformTexture("shadowCubemapArray");
		private final Uniform1i uniMode = addUniform1i("mode");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/shadow_map_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniShadowMap.set(TEXTURE_UNIT_SHADOW_MAP);
			uniTerrainShadowMap.set(TEXTURE_UNIT_TERRAIN_SHADOW_MAP);
			uniPositionalShadowMap.set(TEXTURE_UNIT_POSITIONAL_SHADOW_MAP);
		}
	}

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ClientThread clientThread;

	private static final int MODE_MAIN_SHADOW_MAP = 0;
	private static final int MODE_TERRAIN_SHADOW_MAP = 1;
	private static final int MODE_POSITIONAL_SHADOW_MAP = 2;
	private static final int MODE_COUNT = 8;

	private int mode;

	@Override
	public void setActive(boolean active) {
		super.setActive(active);

		if (active) {
			mouseManager.registerMouseWheelListener(this);
		} else {
			mouseManager.unregisterMouseWheelListener(this);
		}

		// Sampling the depth texture with a non-shadow sampler is undefined behavior, so hide the resulting warning
		if (HdPlugin.GL_CAPS != null && HdPlugin.GL_CAPS.glDebugMessageControl != 0) {
			clientThread.invoke(() -> glDebugMessageControl(
				GL_DEBUG_SOURCE_API, GL_DONT_CARE, GL_DONT_CARE, 0x20096, !active));
		}
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		var b = getBounds();
		if (b != null && b.contains(e.getPoint())) {
			e.consume();
			mode = (mode + 1) % MODE_COUNT;
		}

		return e;
	}

	@Override
	protected void updateUniforms() {
		shader.uniMode.set(mode);
	}

	@Override
	public Dimension render(Graphics2D g) {
		Dimension dim = super.render(g);
		if (!isHidden()) {
			g.setColor(Color.YELLOW);

			final String modeString;
			switch (mode) {
				case MODE_MAIN_SHADOW_MAP:
					modeString = "Main shadow map";
					break;
				case MODE_TERRAIN_SHADOW_MAP:
					modeString = "Terrain shadow map";
					break;
				default:
					modeString = "Positional shadow map " + (mode - MODE_POSITIONAL_SHADOW_MAP);
			};

			HDUtils.drawStringCentered(
				g,
				modeString,
				dim.width / 2.0f,
				dim.height + 10
			);
		}
		return dim;
	}
}
