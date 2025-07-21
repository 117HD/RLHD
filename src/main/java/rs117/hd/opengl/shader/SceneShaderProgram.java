package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;

import static org.lwjgl.opengl.GL33C.*;

public class SceneShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniTextureArray = addUniformProperty("textureArray", GL33C::glUniform1i);
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("shadowMap", GL33C::glUniform1i);

	public SceneShaderProgram() {
		setShader(new Shader()
			.add(GL_VERTEX_SHADER, "vert.glsl")
			.add(GL_GEOMETRY_SHADER, "geom.glsl")
			.add(GL_FRAGMENT_SHADER, "frag.glsl"));
	}
}
