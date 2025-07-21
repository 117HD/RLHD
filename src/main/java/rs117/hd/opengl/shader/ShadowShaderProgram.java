package rs117.hd.opengl.shader;

import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL33C.*;

public class ShadowShaderProgram extends ShaderProgram {
	public Uniform1i uniShadowMap = addUniform1i("textureArray");

	public ShadowShaderProgram(ShadowMode mode) {
		Shader shadowShader = new Shader()
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");
		if (mode == ShadowMode.DETAILED) {
			shadowShader.add(GL_GEOMETRY_SHADER, "shadow_geom.glsl");
		}
		setShader(shadowShader);
	}
}
