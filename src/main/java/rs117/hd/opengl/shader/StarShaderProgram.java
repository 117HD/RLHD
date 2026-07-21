package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

// Point-sprite star pass. Draws the pre-generated star list as GL_POINTS.
public class StarShaderProgram extends ShaderProgram {
	public final Uniform2f uniViewportSize = addUniform2f("viewportSize");

	public StarShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "star_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "star_frag.glsl"));
	}
}
