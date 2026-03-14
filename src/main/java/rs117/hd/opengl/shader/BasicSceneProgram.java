package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class BasicSceneProgram extends ShaderProgram {
	public Uniform1f uniScale = addUniform1f("scale");
	public Uniform4f uniColor = addUniform4f("color");

	public BasicSceneProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "basic_scene_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "basic_scene_frag.glsl"));
	}
}
