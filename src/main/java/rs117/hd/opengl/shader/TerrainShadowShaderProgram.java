package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL33C.*;

public class TerrainShadowShaderProgram extends ShaderProgram {
	public TerrainShadowShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "stub_frag.glsl"));
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy()
			.define("SHADOW_MODE", ShadowMode.FAST)
			.define("TERRAIN_ONLY_PASS", true)
			.define("SHADOW_TRANSPARENCY", false));
	}
}
