package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class StarShaderProgram extends ShaderProgram {
	public StarShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "star_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "star_frag.glsl"));
	}
}
