package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;

public class UIShaderProgram extends ShaderProgram {
	public Uniform1i uniTextureArray = addUniform1i("uniUiTexture");
	public Uniform1i uniShadowMap = addUniform1i("shadowMap");

	public UIShaderProgram() {
		setShader(new Shader()
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "ui_frag.glsl"));
	}
}
