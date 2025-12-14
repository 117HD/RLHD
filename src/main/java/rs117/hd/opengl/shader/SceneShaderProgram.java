package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_TILED_LIGHTING_MAP;
import static rs117.hd.renderer.zone.ZoneRenderer.TEXTURE_UNIT_TEXTURED_FACES;

public class SceneShaderProgram extends ShaderProgram {
	private final UniformTexture uniTextureArray = addUniformTexture("textureArray");
	private final UniformTexture uniShadowMap = addUniformTexture("shadowMap");
	private final UniformTexture uniTiledLightingTextureArray = addUniformTexture("tiledLightingArray");
	private final UniformTexture uniTextureFaces = addUniformTexture("textureFaces");

	public SceneShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "scene_vert.glsl")
			.add(GL_GEOMETRY_SHADER, "scene_geom.glsl")
			.add(GL_FRAGMENT_SHADER, "scene_frag.glsl"));
		uniTiledLightingTextureArray.ignoreMissing = true;
	}

	public SceneShaderProgram useGeom(boolean useGeom) {
		if (useGeom) {
			shaderTemplate.add(GL_GEOMETRY_SHADER, "scene_geom.glsl");
		} else {
			shaderTemplate.remove(GL_GEOMETRY_SHADER);
		}
		return this;
	}

	@Override
	protected void initialize() {
		uniTextureArray.set(TEXTURE_UNIT_GAME);
		uniShadowMap.set(TEXTURE_UNIT_SHADOW_MAP);
		uniTiledLightingTextureArray.set(TEXTURE_UNIT_TILED_LIGHTING_MAP);
		uniTextureFaces.set(TEXTURE_UNIT_TEXTURED_FACES);
	}
}
