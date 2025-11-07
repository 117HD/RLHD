package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.GLBinding.IMAGE_TILE_LIGHTING_MAP;
import static rs117.hd.opengl.GLBinding.TEXTURE_TILE_LIGHTING_MAP;

public class TiledLightingShaderProgram extends ShaderProgram {
	private final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	private final UniformImage uniTiledLightingTextureStore = addUniformImage("tiledLightingImage");

	public TiledLightingShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "tiled_lighting_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "tiled_lighting_frag.glsl"));
		uniTiledLightingTextureArray.ignoreMissing = true;
		uniTiledLightingTextureStore.ignoreMissing = true;
	}

	@Override
	protected void initialize() {
		uniTiledLightingTextureArray.set(TEXTURE_TILE_LIGHTING_MAP);
		uniTiledLightingTextureStore.set(IMAGE_TILE_LIGHTING_MAP);
	}
}
