package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class SkyShaderProgram extends ShaderProgram {
	public SkyShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "sky_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "sky_frag.glsl"));
	}

	@Override
	protected void initialize() {
		// No textures needed for sky gradient
	}
}
