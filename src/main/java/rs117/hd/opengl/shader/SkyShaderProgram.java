package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import rs117.hd.HdPlugin;

public class SkyShaderProgram extends ShaderProgram {
	protected final UniformTexture uniNightSkyTexture = addUniformTexture("nightSkyTexture");

	public SkyShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "sky_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "sky_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniNightSkyTexture.set(HdPlugin.TEXTURE_UNIT_NIGHT_SKY);
	}
}
