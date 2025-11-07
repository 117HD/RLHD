package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.GLBinding.STORAGE_MODEL_DATA;
import static rs117.hd.opengl.GLBinding.TEXTURE_GAME;
import static rs117.hd.opengl.GLBinding.TEXTURE_SHADOW_MAP;
import static rs117.hd.opengl.GLBinding.TEXTURE_TILE_LIGHTING_MAP;

public class SceneShaderProgram extends ShaderProgram {
	private final UniformTexture uniTextureArray = addUniformTexture("textureArray");
	private final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
	private final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	private final UniformTexture uniModelDataBuffer = addUniformTexture("modelDataBuffer");

	public SceneShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "scene_vert.glsl")
			.add(GL_GEOMETRY_SHADER, "scene_geom.glsl")
			.add(GL_FRAGMENT_SHADER, "scene_frag.glsl"));
		uniTiledLightingTextureArray.ignoreMissing = true;
	}

	@Override
	protected void initialize() {
		uniTextureArray.set(TEXTURE_GAME);
		uniShadowMap.set(TEXTURE_SHADOW_MAP);
		uniTiledLightingTextureArray.set(TEXTURE_TILE_LIGHTING_MAP);
		uniModelDataBuffer.set(STORAGE_MODEL_DATA);
	}
}
