package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_BASE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;
import static rs117.hd.HdPlugin.TILED_LIGHTING_STORE;

public class TiledLightingShaderProgram extends ShaderProgram {
	private final Uniform1i uniTiledLightingTextureArray = addUniform1i("tiledLightingArray");
	private final Uniform1i uniTiledLightingTextureStore = addUniform1i("tiledLightingImage");

	public TiledLightingShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "tiled_lighting_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "tiled_lighting_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniTiledLightingTextureArray.set(TEXTURE_UNIT_TILED_LIGHTING_MAP - TEXTURE_UNIT_BASE);
		uniTiledLightingTextureStore.set(TILED_LIGHTING_STORE);
	}
}
