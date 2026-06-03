package rs117.hd.overlays;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseWheelEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import rs117.hd.utils.HDUtils;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class ReflectionMapOverlay extends ShaderOverlay<ReflectionMapOverlay.Shader> implements MouseWheelListener {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniColorMap = addUniformTexture("colorMap");
		private final Uniform1i uniTextureLayer = addUniform1i("layer");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/color_map_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniColorMap.set(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		}
	}

	@Inject
	private MouseManager mouseManager;

	int textureLayer;
	int numTextureLayers;

	@Override
	public void initialize() {
		super.initialize();
		mouseManager.registerMouseWheelListener(this);
	}

	@Override
	public void destroy() {
		mouseManager.unregisterMouseWheelListener(this);
		numTextureLayers = 0;
		super.destroy();
	}

	@Override
	protected void renderShader() {
		if (numTextureLayers == 0) {
			glActiveTexture(TEXTURE_UNIT_WATER_REFLECTION_MAP);
			int bound = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
			if (bound != 0)
				numTextureLayers = glGetTexLevelParameteri(GL_TEXTURE_2D_ARRAY, 0, GL_TEXTURE_DEPTH);
		}

		shader.uniTextureLayer.set(textureLayer);
		super.renderShader();
	}

	@Override
	public Dimension render(Graphics2D g) {
		var dims = super.render(g);

		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(Color.YELLOW);
		HDUtils.drawStringShadowed(g, String.format("Layer %d/%d", textureLayer + 1, numTextureLayers), 4, 18);

		return dims;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		if (numTextureLayers > 0 && getBounds().contains(e.getPoint())) {
			int scroll = clamp(e.getWheelRotation(), -1, 1);
			textureLayer = clamp(textureLayer + scroll, 0, numTextureLayers - 1);
			e.consume();
		}

		return e;
	}
}
