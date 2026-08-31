package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class DepthShaderProgram extends ShaderProgram {

	public UniformMat4 uniViewProjection = addUniformMat4("viewProjection");

	public DepthShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "depth_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "depth_frag.glsl"));
	}
}
