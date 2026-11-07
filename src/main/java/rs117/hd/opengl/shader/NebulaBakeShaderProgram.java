package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class NebulaBakeShaderProgram extends ShaderProgram {
	public final Uniform1i uniCubeFace = addUniform1i("cubeFace");

	public NebulaBakeShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "sky_nebula_bake_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "sky_nebula_bake_frag.glsl"));
	}
}
