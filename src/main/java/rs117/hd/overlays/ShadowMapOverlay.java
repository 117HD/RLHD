package rs117.hd.overlays;

import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.GLBinding;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
@Singleton
public class ShadowMapOverlay extends ShaderOverlay<ShadowMapOverlay.Shader> {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniShadowMap = addUniformTexture("shadowMap");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/shadow_map_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniShadowMap.set(GLBinding.TEXTURE_SHADOW_MAP);
		}
	}
}
