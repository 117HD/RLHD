package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TEMPORARY;

public class BlurShaderProgram extends ShaderProgram {
	public final UniformTexture uniTexture = addUniformTexture("uniTexture");
	public final Uniform1i uniMipLevel = addUniform1i("uniMipLevel");
	public final Uniform1i uniDirection = addUniform1i("uniDirection");

	public BlurShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "default_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "blur_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniTexture.set(TEXTURE_UNIT_TEMPORARY);
	}
}
