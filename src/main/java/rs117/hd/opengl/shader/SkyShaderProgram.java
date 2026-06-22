package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_NEBULA;

public class SkyShaderProgram extends ShaderProgram {
	protected final UniformTexture uniNebulaMap = addUniformTexture("nebulaMap");

	public SkyShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "sky_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "sky_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniNebulaMap.set(TEXTURE_UNIT_NEBULA);
	}
}
