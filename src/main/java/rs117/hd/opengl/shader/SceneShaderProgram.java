package rs117.hd.opengl.shader;

import org.lwjgl.opengl.GL33;

public class SceneShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniTextureArray = addUniformProperty("textureArray", GL33::glUniform1i);
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("shadowMap", GL33::glUniform1i);

	public SceneShaderProgram(Shader shader) {
		setShader(shader);
	}
}
