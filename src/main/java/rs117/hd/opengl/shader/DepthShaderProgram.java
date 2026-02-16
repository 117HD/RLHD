package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

public class DepthShaderProgram extends ShaderProgram {
	protected final UniformTexture uniTextureFaces = addUniformTexture("textureFaces");

	public DepthShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "depth_vert.glsl"));
	}

	@Override
	protected void initialize() {
		uniTextureFaces.set(TEXTURE_UNIT_TEXTURED_FACES);
	}
}
