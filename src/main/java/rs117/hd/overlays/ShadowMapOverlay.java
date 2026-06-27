package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseWheelEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TERRAIN_SHADOW_MAP;
import static rs117.hd.utils.HDUtils.drawStringCentered;

@Slf4j
@Singleton
public class ShadowMapOverlay extends ShaderOverlay<ShadowMapOverlay.Shader> implements MouseWheelListener {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
		private final UniformTexture uniTerrainShadowMap = addUniformTexture("terrainShadowMap");
		private final UniformBool uniShowTerrainShadowMap = addUniformBool("showTerrainShadowMap");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/shadow_map_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniShadowMap.set(TEXTURE_UNIT_SHADOW_MAP);
			uniTerrainShadowMap.set(TEXTURE_UNIT_TERRAIN_SHADOW_MAP);
		}
	}

	@Inject
	private Client client;

	@Inject
	private MouseManager mouseManager;

	private boolean showTerrainShadowMap;

	@Override
	public void setActive(boolean active) {
		super.setActive(active);

		if(active) {
			mouseManager.registerMouseWheelListener(this);
		} else {
			mouseManager.unregisterMouseWheelListener(this);
		}
	}

	@Override
	protected void updateUniforms() {
		shader.uniShowTerrainShadowMap.set(showTerrainShadowMap);
	}

	@Override
	public Dimension render(Graphics2D g) {
		Dimension dim = super.render(g);
		if (!super.isHidden()) {
			g.setColor(Color.YELLOW);
			drawStringCentered(g,
				showTerrainShadowMap ?
					"Terrain Shadow map" :
					"Main Shadow map",
				dim.width / 2.0f,
				dim.height + 10);
		}
		return dim;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		if (client.isKeyPressed(KeyCode.KC_CONTROL)) {
			e.consume();

			showTerrainShadowMap = !showTerrainShadowMap;
		}

		return e;
	}
}
