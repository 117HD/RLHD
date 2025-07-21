package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class SceneShaderProgram extends ShaderProgram {
	public Uniform1i uniTextureArray = addUniform1i("textureArray");
	public Uniform1i uniShadowMap = addUniform1i("shadowMap");

	public SceneShaderProgram() {
		setShaderTemplate(new ShaderTemplate()
			.add(GL_VERTEX_SHADER, "vert.glsl")
			.add(GL_GEOMETRY_SHADER, "geom.glsl")
			.add(GL_FRAGMENT_SHADER, "frag.glsl"));
	}
}
