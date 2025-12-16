package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.config.ShadowMode;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

public class ShadowShaderProgram extends ShaderProgram {
	private ShadowMode mode;
	private final UniformTexture uniShadowMap = addUniformTexture("textureArray");
	private final UniformTexture uniTextureFaces = addUniformTexture("textureFaces");

	public ShadowShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniShadowMap.set(TEXTURE_UNIT_GAME);
		uniTextureFaces.set(TEXTURE_UNIT_TEXTURED_FACES);
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("SHADOW_MODE", mode));
	}

	public void setMode(ShadowMode mode, boolean shouldUseGeom) {
		this.mode = mode;
		if (mode == ShadowMode.DETAILED && shouldUseGeom) {
			shaderTemplate.add(GL_GEOMETRY_SHADER, "shadow_geom.glsl");
		} else {
			shaderTemplate.remove(GL_GEOMETRY_SHADER);
		}
	}

	public static class Fast extends ShadowShaderProgram {
		public Fast() {
			super();
			setMode(ShadowMode.FAST, true);
		}
	}

	public static class Detailed extends ShadowShaderProgram {
		public Detailed() {
			super();
			setMode(ShadowMode.DETAILED, true);
		}
	}

	public static class DetailedNoGeom extends ShadowShaderProgram {
		public DetailedNoGeom() {
			super();
			setMode(ShadowMode.DETAILED, false);
		}
	}
}
