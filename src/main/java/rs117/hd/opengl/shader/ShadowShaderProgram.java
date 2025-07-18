package rs117.hd.opengl.shader;

import org.lwjgl.opengl.GL33;

public class ShadowShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("textureArray", GL33::glUniform1i);

	public ShadowShaderProgram(Shader shader) {
		setShader(shader);
	}
}
