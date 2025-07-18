package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

public class UIShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniTextureArray = addUniformProperty("uniUiTexture", GL33::glUniform1i);
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("shadowMap", GL33::glUniform1i);

	public UIShaderProgram() {
		setShader(new Shader()
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "ui_frag.glsl"));
	}
}
