package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

// One-time bake of the procedural nebula into a cubemap. Rendered once per cube
// face with the face's basis set via the faceForward/faceRight/faceUp uniforms.
public class NebulaBakeShaderProgram extends ShaderProgram {
	public final Uniform3f uniFaceForward = addUniform3f("faceForward");
	public final Uniform3f uniFaceRight = addUniform3f("faceRight");
	public final Uniform3f uniFaceUp = addUniform3f("faceUp");

	public NebulaBakeShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "sky_nebula_bake_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "sky_nebula_bake_frag.glsl"));
	}
}
