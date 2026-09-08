package rs117.hd.opengl.shader;

import java.io.IOException;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;
import static rs117.hd.renderer.zone.ZoneRenderer.OIT_BIN_COUNT;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_OIT_FIRST_LAYER;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

public class SceneShaderProgram extends ShaderProgram {
	protected final UniformTexture uniTextureArray = addUniformTexture("textureArray");
	protected final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
	protected final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	protected final UniformTexture uniTextureFaces = addUniformTexture("textureFaces");

	public SceneShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "scene_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "scene_frag.glsl"));
		uniTiledLightingTextureArray.ignoreMissing = true;
	}
	@Override
	protected void initialize() {
		uniTextureArray.set(TEXTURE_UNIT_GAME);
		uniShadowMap.set(TEXTURE_UNIT_SHADOW_MAP);
		uniTiledLightingTextureArray.set(TEXTURE_UNIT_TILED_LIGHTING_MAP);
		uniTextureFaces.set(TEXTURE_UNIT_TEXTURED_FACES);
	}

	public static class Opaque extends SceneShaderProgram {
		Opaque() { shaderTemplate.add(GL_FRAGMENT_SHADER, "scene_frag_opaque.glsl"); }
	}

	public static class TransparentOIT extends SceneShaderProgram {
		protected final UniformTexture uniFirstLayerDepth = addUniformTexture("firstLayerDepth");

		TransparentOIT() {
			shaderTemplate.add(GL_FRAGMENT_SHADER, "scene_frag_oit.glsl");
			uniFirstLayerDepth.ignoreMissing = true;
		}

		@Override
		protected void initialize() {
			super.initialize();
			uniFirstLayerDepth.set(TEXTURE_UNIT_OIT_FIRST_LAYER);
		}

		@Override
		public void compile(ShaderIncludes includes) throws ShaderException, IOException {
			super.compile(includes.copy()
				.addInclude("OIT_MRT_OUTPUTS", this::generateOitMrtOutputs)
				.addInclude("OIT_MRT_INIT", this::generateOitMrtInit)
				.addInclude("OIT_MRT_WRITE", this::generateOitMrtWrite));
		}

		private String generateOitMrtOutputs() {
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < OIT_BIN_COUNT; k++) {
				sb.append("layout(location = ").append(k).append(") out vec4 colorAccum").append(k).append(";\n");
			}
			return sb.toString();
		}

		private String generateOitMrtInit() {
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < OIT_BIN_COUNT; k++)
				sb.append("colorAccum").append(k).append(" = vec4(0.0);\n");
			return sb.toString();
		}

		private String generateOitMrtWrite() {
			StringBuilder sb = new StringBuilder();
			for (int k = 0; k < OIT_BIN_COUNT; k++) {
				sb
					.append("if (k == ").append(k).append(") ")
					.append("colorAccum").append(k)
					.append(" = vec4(outputColor.rgb * outputColor.a * bw, outputColor.a * bw);\n");
			}
			return sb.toString();
		}
	}

	public static class AlphaDiscard extends SceneShaderProgram {
		AlphaDiscard() {
			shaderTemplate.add(GL_FRAGMENT_SHADER, "scene_frag_alpha_discard.glsl");
		}
	}

	public static class Legacy extends SceneShaderProgram {
		Legacy() {
			shaderTemplate.add(GL_GEOMETRY_SHADER, "scene_geom.glsl");
			uniTextureFaces.ignoreMissing = true;
		}
	}
}
