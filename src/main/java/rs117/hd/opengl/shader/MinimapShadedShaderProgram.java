package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class MinimapShadedShaderProgram extends ShaderProgram {
	public final UniformMat4 uniViewProjMatrix = addUniformMat4("viewProjMatrix");

	public MinimapShadedShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "minimap_shaded_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "minimap_shaded_frag.glsl"));
	}
}
