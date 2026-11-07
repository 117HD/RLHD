package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.IMAGE_UNIT_TILED_LIGHTING;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SCENE_DEPTH;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;
import static rs117.hd.renderer.zone.OitRenderer.TEXTURE_UNIT_FIRST_LAYER;

public class TiledLightingShaderProgram extends ShaderProgram {
	private final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	private final UniformImage uniTiledLightingTextureStore = addUniformImage("tiledLightingImage");
	private final UniformTexture uniSceneDepth = addUniformTexture("sceneDepthTexture");
	private final UniformTexture uniAlphaDepth = addUniformTexture("alphaFirstLastDepth");
	private final UniformBool uniHasAlphaDepth = addUniformBool("hasAlphaDepth");

	public TiledLightingShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "tiled_lighting_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "tiled_lighting_frag.glsl"));
		uniTiledLightingTextureArray.ignoreMissing = true;
		uniTiledLightingTextureStore.ignoreMissing = true;
		uniSceneDepth.ignoreMissing = true;
		uniAlphaDepth.ignoreMissing = true;
		uniHasAlphaDepth.ignoreMissing = true;
	}

	@Override
	protected void initialize() {
		uniTiledLightingTextureArray.set(TEXTURE_UNIT_TILED_LIGHTING_MAP);
		uniTiledLightingTextureStore.set(IMAGE_UNIT_TILED_LIGHTING);
		uniSceneDepth.set(TEXTURE_UNIT_SCENE_DEPTH);
		uniAlphaDepth.set(TEXTURE_UNIT_FIRST_LAYER);
	}

	public void setHasAlphaDepth(boolean hasAlphaDepth) {
		uniHasAlphaDepth.set(hasAlphaDepth);
	}
}
