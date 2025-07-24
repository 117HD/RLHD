package rs117.hd.overlays;

import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.OverlayLayer;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_BASE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;

@Slf4j
@Singleton
public class TiledLightingOverlay extends ShaderOverlay<TiledLightingOverlay.Shader> {
	static class Shader extends ShaderOverlay.Shader {
		private final Uniform1i uniTiledLightingTextureArray = addUniform1i("tiledLightingArray");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/tiled_lighting_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniTiledLightingTextureArray.set(TEXTURE_UNIT_TILED_LIGHTING_MAP - TEXTURE_UNIT_BASE);
		}
	}

	public TiledLightingOverlay() {
		setFullscreen(true);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}
}
