package rs117.hd.opengl.shader;

import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL33C.*;

public class OitDepthMergeShaderProgram extends ResolveShaderProgram {
	private final UniformTexture uniFirstLayerDepth = addUniformTexture("firstLayerDepth");
	private final UniformTexture uniOpaqueSceneDepth = addUniformTexture("opaqueSceneDepth");

	public OitDepthMergeShaderProgram() {
		super("oit_depth_merge_frag.glsl", "OIT Depth Merge FBO");
	}

	public void merge(
		RenderState renderState,
		int resolveVao,
		int firstLayerTextureUnit,
		int opaqueDepthTextureUnit,
		int firstLayerDepth,
		int opaqueSceneDepth,
		int destinationTexture
	) {
		glActiveTexture(firstLayerTextureUnit);
		glBindTexture(GL_TEXTURE_2D, firstLayerDepth);
		glActiveTexture(opaqueDepthTextureUnit);
		glBindTexture(GL_TEXTURE_2D, opaqueSceneDepth);

		use();
		uniFirstLayerDepth.set(firstLayerTextureUnit);
		uniOpaqueSceneDepth.set(opaqueDepthTextureUnit);

		super.resolve(renderState, resolveVao, destinationTexture);
	}
}
