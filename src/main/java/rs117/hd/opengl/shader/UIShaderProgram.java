package rs117.hd.opengl.shader;

import org.lwjgl.opengl.GL33;

public class UIShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniTextureArray = addUniformProperty("uniUiTexture", GL33::glUniform1i);
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("shadowMap", GL33::glUniform1i);

	public UIShaderProgram(Shader shader) {
		setShader(shader);
	}
}
