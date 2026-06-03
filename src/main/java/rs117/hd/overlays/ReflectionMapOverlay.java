package rs117.hd.overlays;

import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;

@Slf4j
@Singleton
public class ReflectionMapOverlay extends ShaderOverlay<ReflectionMapOverlay.Shader> {
	static class Shader extends ShaderOverlay.Shader {
		private final UniformTexture uniColorMap = addUniformTexture("colorMap");

		public Shader() {
			super(t -> t.add(GL_FRAGMENT_SHADER, "overlays/color_map_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniColorMap.set(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		}
	}
}
