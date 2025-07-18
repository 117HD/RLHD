package rs117.hd.opengl.shader;

import org.lwjgl.opengl.*;
import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL32C.GL_GEOMETRY_SHADER;

public class ShadowShaderProgram extends ShaderProgram {
	public UniformProperty<Integer> uniShadowMap = addUniformProperty("textureArray", GL33::glUniform1i);

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
