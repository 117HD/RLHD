package rs117.hd.opengl.shader;

import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL33C.*;

public class OitDepthMergeShaderProgram extends ResolveShaderProgram {
	private final UniformTexture uniFirstLayerDepth = addUniformTexture("firstLayerDepth");
	private final UniformTexture uniFarthestLayerDepth = addUniformTexture("farthestLayerDepth");

	public OitDepthMergeShaderProgram() {
		super("oit_depth_merge_frag.glsl", "OIT Depth Merge FBO");
	}

	public void merge(
		RenderState renderState,
		int resolveVao,
		int firstLayerTextureUnit,
		int farthestLayerTextureUnit,
		int firstLayerDepth,
		int farthestLayerDepth,
		int destinationTexture
	) {
		glActiveTexture(firstLayerTextureUnit);
		glBindTexture(GL_TEXTURE_2D, firstLayerDepth);
		glActiveTexture(farthestLayerTextureUnit);
		glBindTexture(GL_TEXTURE_2D, farthestLayerDepth);

		use();
		uniFirstLayerDepth.set(firstLayerTextureUnit);
		uniFarthestLayerDepth.set(farthestLayerTextureUnit);

		super.resolve(renderState, resolveVao, destinationTexture);
	}
}
