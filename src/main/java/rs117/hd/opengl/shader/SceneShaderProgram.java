package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SCENE_ALPHA_DEPTH;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SCENE_OPAQUE_DEPTH;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

public class SceneShaderProgram extends ShaderProgram {
	protected final UniformTexture uniTextureArray = addUniformTexture("textureArray");
	protected final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
	protected final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	protected final UniformTexture uniTextureFaces = addUniformTexture("textureFaces");
	protected final UniformTexture uniSceneOpaqueDepth = addUniformTexture("sceneOpaqueDepth");
	protected final UniformTexture uniSceneAlphaDepth = addUniformTexture("sceneAlphaDepth");

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
		uniSceneOpaqueDepth.set(TEXTURE_UNIT_SCENE_OPAQUE_DEPTH);
		uniSceneAlphaDepth.set(TEXTURE_UNIT_SCENE_ALPHA_DEPTH);
	}

	public static class Legacy extends SceneShaderProgram {
		Legacy() {
			shaderTemplate.add(GL_GEOMETRY_SHADER, "scene_geom.glsl");
			uniTextureFaces.ignoreMissing = true;
		}
	}
}
