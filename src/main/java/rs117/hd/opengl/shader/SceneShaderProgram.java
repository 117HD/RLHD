package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.GLBinding.BINDING_IMG_TILE_LIGHTING_MAP;
import static rs117.hd.opengl.GLBinding.BINDING_SSAO_MODEL_DATA;
import static rs117.hd.opengl.GLBinding.BINDING_TEX_GAME;
import static rs117.hd.opengl.GLBinding.BINDING_TEX_SHADOW_MAP;

public class SceneShaderProgram extends ShaderProgram {
	private final UniformTexture uniTextureArray = addUniformTexture("textureArray");
	private final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
	private final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	private final UniformTexture uniModelDataSampler = addUniformTexture("ModelDataSampler");

	public SceneShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "scene_vert.glsl")
			.add(GL_GEOMETRY_SHADER, "scene_geom.glsl")
			.add(GL_FRAGMENT_SHADER, "scene_frag.glsl"));
		uniTiledLightingTextureArray.ignoreMissing = true;
	}

	@Override
	protected void initialize() {
		uniTextureArray.set(BINDING_TEX_GAME);
		uniShadowMap.set(BINDING_TEX_SHADOW_MAP);
		uniTiledLightingTextureArray.set(BINDING_IMG_TILE_LIGHTING_MAP);
		uniModelDataSampler.set(BINDING_SSAO_MODEL_DATA);
	}
}
