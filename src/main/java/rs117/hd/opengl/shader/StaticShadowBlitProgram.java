package rs117.hd.opengl.shader;

import javax.inject.Singleton;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;

@Singleton
public class StaticShadowBlitProgram extends ShaderProgram {

	public UniformTexture staticCache = addUniformTexture("staticCache");

	public Uniform1i face = addUniform1i("face");
	public Uniform1i atlasSize = addUniform1i("atlasSize");
	public Uniform1i maxFaceResolution = addUniform1i("maxFaceResolution");

	public StaticShadowBlitProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "static_shadow_blit_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "static_shadow_blit_frag.glsl"));
	}
}
