package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class SceneDepthShaderProgram extends ShaderProgram {
	public SceneDepthShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "depth_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "stub_frag.glsl"));
	}
}
