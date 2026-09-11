package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class DepthSceneShaderProgram extends ShaderProgram {
	public DepthSceneShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "depth_scene_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "depth_scene_frag.glsl"));
	}
}
