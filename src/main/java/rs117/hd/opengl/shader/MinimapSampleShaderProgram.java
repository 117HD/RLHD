package rs117.hd.opengl.shader;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_MINIMAP_CACHE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_MINIMAP_MASK;

public class MinimapSampleShaderProgram extends ShaderProgram {
	public final UniformTexture uniCacheTexture = addUniformTexture("cacheTexture");
	public final UniformTexture uniMaskTexture = addUniformTexture("maskTexture");
	public final Uniform2f uniFboSize = addUniform2f("fboSize");
	public final Uniform1f uniCosYaw = addUniform1f("cosYaw");
	public final Uniform1f uniSinYaw = addUniform1f("sinYaw");
	public final Uniform1f uniPixelToWorld = addUniform1f("pixelToWorld");
	public final Uniform2f uniCacheCenterUv = addUniform2f("cacheCenterUv");
	public final Uniform1f uniCacheUvPerWorldUnit = addUniform1f("cacheUvPerWorldUnit");

	public MinimapSampleShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "minimap_sample_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "minimap_sample_frag.glsl"));
	}

	@Override
	protected void initialize() {
		uniCacheTexture.set(TEXTURE_UNIT_MINIMAP_CACHE);
		uniMaskTexture.set(TEXTURE_UNIT_MINIMAP_MASK);
	}
}
