package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_BASE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SKYBOX;

public class SceneShaderProgram extends ShaderProgram {
	public Uniform1i uniTextureArray = addUniform1i("textureArray");
	public Uniform1i uniShadowMap = addUniform1i("shadowMap");
	private final Uniform1i uniSkyboxArray = addUniform1i("skyboxArray");

	public SceneShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "scene_vert.glsl")
			.add(GL_GEOMETRY_SHADER, "scene_geom.glsl")
			.add(GL_FRAGMENT_SHADER, "scene_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniTextureArray.set(TEXTURE_UNIT_GAME - TEXTURE_UNIT_BASE);
		uniShadowMap.set(TEXTURE_UNIT_SHADOW_MAP - TEXTURE_UNIT_BASE);
		uniSkyboxArray.set(TEXTURE_UNIT_SKYBOX - TEXTURE_UNIT_BASE);
	}
}
