package rs117.hd.opengl.shader;

import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_BASE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;

public class ShadowShaderProgram extends ShaderProgram {
	private final Uniform1i uniShadowMap = addUniform1i("textureArray");

	public ShadowShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniShadowMap.set(TEXTURE_UNIT_GAME - TEXTURE_UNIT_BASE);
	}

	public void setMode(ShadowMode mode) {
		if (mode == ShadowMode.DETAILED) {
			shaderTemplate.add(GL_GEOMETRY_SHADER, "shadow_geom.glsl");
		} else {
			shaderTemplate.remove(GL_GEOMETRY_SHADER);
		}
	}
}
