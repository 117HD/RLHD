package rs117.hd.opengl.shader;

import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL33C.*;

public class ShadowShaderProgram extends ShaderProgram {
	public Uniform1i uniShadowMap = addUniform1i("textureArray");
	private ShadowMode currentMode = ShadowMode.OFF;

	public void setMode(ShadowMode mode) {
		if (currentMode == mode)
			return;

		currentMode = mode;
		var shaderTemplate = new ShaderTemplate()
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");
		if (mode == ShadowMode.DETAILED)
			shaderTemplate.add(GL_GEOMETRY_SHADER, "shadow_geom.glsl");
		setShaderTemplate(shaderTemplate);
	}
}
