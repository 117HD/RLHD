package rs117.hd.opengl.shader;

import java.io.IOException;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_NORMAL_MAPS;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

public class SceneShaderProgram extends ShaderProgram {
	public static final int RENDER_PASS_MAIN = 0;
	public static final int RENDER_PASS_REFLECTION = 1;
	public static final int RENDER_PASS_WATER = 2;

	protected final UniformTexture uniTextureArray = addUniformTexture("textureArray");
	protected final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
	protected final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	protected final UniformTexture uniTextureFaces = addUniformTexture("textureFaces");
	private final UniformTexture uniWaterReflectionMap = addUniformTexture("waterReflectionMap");
	private final UniformTexture uniWaterNormalMaps = addUniformTexture("waterNormalMaps");

	private final int renderPass;

	private SceneShaderProgram(int renderPass) {
		super(t -> t
			.add(GL_VERTEX_SHADER, "scene_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "scene_frag.glsl"));
		this.renderPass = renderPass;
		uniTiledLightingTextureArray.ignoreMissing = true;
	}

	@Override
	public void compile(ShaderIncludes includes) throws ShaderException, IOException {
		super.compile(includes.copy().define("RENDER_PASS", renderPass));
	}

	@Override
	protected void initialize() {
		uniTextureArray.set(TEXTURE_UNIT_GAME);
		uniShadowMap.set(TEXTURE_UNIT_SHADOW_MAP);
		uniTiledLightingTextureArray.set(TEXTURE_UNIT_TILED_LIGHTING_MAP);
		uniTextureFaces.set(TEXTURE_UNIT_TEXTURED_FACES);
		uniWaterReflectionMap.set(TEXTURE_UNIT_WATER_REFLECTION_MAP);
		uniWaterNormalMaps.set(TEXTURE_UNIT_WATER_NORMAL_MAPS);
	}

	public static class Legacy extends SceneShaderProgram {
		public Legacy() {
			super(RENDER_PASS_MAIN);
			shaderTemplate.add(GL_GEOMETRY_SHADER, "scene_geom.glsl");
			uniTextureFaces.ignoreMissing = true;
		}
	}

	public static class ZoneMain extends SceneShaderProgram {
		public ZoneMain() {
			super(RENDER_PASS_MAIN);
		}
	}

	public static class ZoneReflection extends SceneShaderProgram {
		public ZoneReflection() {
			super(RENDER_PASS_REFLECTION);
		}
	}

	public static class ZoneWater extends SceneShaderProgram {
		public ZoneWater() {
			super(RENDER_PASS_WATER);
		}
	}
}
