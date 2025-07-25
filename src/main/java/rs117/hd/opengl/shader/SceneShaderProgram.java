package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_BASE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_GAME;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_NORMAL_MAPS;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_WATER_REFLECTION_MAP;

public class SceneShaderProgram extends ShaderProgram {
	public static final int RENDER_PASS_MAIN = 0;
	public static final int RENDER_PASS_REFLECTION = 1;

	private final Uniform1i uniTextureArray = addUniform1i("textureArray");
	private final Uniform1i uniShadowMap = addUniform1i("shadowMap");
	private final Uniform1i uniWaterReflectionMap = addUniform1i("waterReflectionMap");
	private final Uniform1i uniWaterNormalMaps = addUniform1i("waterNormalMaps");

	public Uniform1i uniRenderPass = addUniform1i("renderPass");
	public Uniform4i uniViewport = addUniform4i("viewport");
	public Uniform1i uniWaterHeight = addUniform1i("waterHeight");
	public UniformBool uniWaterReflectionEnabled = addUniformBool("waterReflectionEnabled");
	public UniformBool uniShorelineCaustics = addUniformBool("shorelineCaustics");
	public UniformBool uniWaterTransparency = addUniformBool("waterTransparency");
	public Uniform3f uniLegacyWaterColor = addUniform3f("legacyWaterColor");

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
		uniWaterReflectionMap.set(TEXTURE_UNIT_WATER_REFLECTION_MAP - TEXTURE_UNIT_BASE);
		uniWaterNormalMaps.set(TEXTURE_UNIT_WATER_NORMAL_MAPS - TEXTURE_UNIT_BASE);
	}
}
