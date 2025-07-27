package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.OverlayLayer;
import rs117.hd.HdPlugin;
import rs117.hd.config.DynamicLights;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;

@Slf4j
@Singleton
public class TiledLightingOverlay extends ShaderOverlay<TiledLightingOverlay.Shader> {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/tiled_lighting_overlay_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniTiledLightingTextureArray.set(TEXTURE_UNIT_TILED_LIGHTING_MAP);
		}
	}

	@Inject
	private HdPlugin plugin;

	public TiledLightingOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
		setFullscreen(true);
	}

	@Override
	public boolean isHidden() {
		return super.isHidden() || plugin.configDynamicLights == DynamicLights.NONE || !plugin.configTiledLighting;
	}

	@Override
	public Dimension render(Graphics2D g) {
		if (!super.isHidden()) {
			g.setColor(Color.YELLOW);
			if (plugin.configDynamicLights == DynamicLights.NONE) {
				drawStringCentered(g, "Dynamic lights are disabled");
			} else if (!plugin.configTiledLighting) {
				drawStringCentered(g, "Tiled lighting is disabled");
			}
		}
		return super.render(g);
	}
}
