package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.GLBinding.TEXTURE_GAME;

public class ShadowShaderProgram extends ShaderProgram {
	private ShadowMode mode;
	private final UniformTexture uniTextureArray = addUniformTexture("textureArray");

	public ShadowShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniTextureArray.set(TEXTURE_GAME);
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("SHADOW_MODE", mode));
	}

	public void setMode(ShadowMode mode) {
		this.mode = mode;
		if (mode == ShadowMode.DETAILED) {
			shaderTemplate.add(GL_GEOMETRY_SHADER, "shadow_geom.glsl");
		} else {
			shaderTemplate.remove(GL_GEOMETRY_SHADER);
		}
	}

	public static class Fast extends ShadowShaderProgram {
		public Fast() {
			super();
			setMode(ShadowMode.FAST);
		}
	}

	public static class Detailed extends ShadowShaderProgram {
		public Detailed() {
			super();
			setMode(ShadowMode.DETAILED);
		}
	}
}
