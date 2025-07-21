package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;

import static org.lwjgl.opengl.GL33C.*;

public class UIShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniTextureArray = addUniformProperty("uniUiTexture", GL33C::glUniform1i);
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("shadowMap", GL33C::glUniform1i);

	public UIShaderProgram() {
		setShader(new Shader()
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "ui_frag.glsl"));
	}
}
