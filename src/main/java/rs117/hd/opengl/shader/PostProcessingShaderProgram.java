package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TEMPORARY;

public class PostProcessingShaderProgram extends ShaderProgram {
	public final UniformTexture uniTexture = addUniformTexture("uniTexture");

	public PostProcessingShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "default_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "post_processing_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniTexture.set(TEXTURE_UNIT_TEMPORARY);
	}
}
