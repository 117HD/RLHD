package rs117.hd.overlays;

import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_POSITIONAL_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;

@Slf4j
@Singleton
public class ShadowMapOverlay extends ShaderOverlay<ShadowMapOverlay.Shader> {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
		private final UniformTexture uniPositionalShadowMap = addUniformTexture("positionalShadowMap");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/shadow_map_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniShadowMap.set(TEXTURE_UNIT_SHADOW_MAP);
			uniPositionalShadowMap.set(TEXTURE_UNIT_POSITIONAL_SHADOW_MAP);
		}
	}
}
