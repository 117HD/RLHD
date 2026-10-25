package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.Utils.labelObject;

public class OitDepthMergeShaderProgram extends ShaderProgram {
	private int resolveFBO;
	private final UniformTexture uniFirstLayerDepth = addUniformTexture("firstLayerDepth");
	private final UniformTexture uniFarthestLayerDepth = addUniformTexture("farthestLayerDepth");

	public OitDepthMergeShaderProgram() {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "oit_depth_merge_frag.glsl"));
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

		resolve(renderState, resolveVao, destinationTexture);
	}

	protected void resolve(
		RenderState renderState,
		int resolveVao,
		int destinationTexture
	) {
		if (resolveFBO == 0) {
			resolveFBO = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, resolveFBO);
			labelObject(GL_FRAMEBUFFER, resolveFBO, "OIT Depth Merge FBO");
		}

		renderState.framebuffer.set(GL_FRAMEBUFFER, resolveFBO);
		renderState.disable.set(GL_MULTISAMPLE);
		renderState.disable.set(GL_BLEND);
		renderState.disable.set(GL_DEPTH_TEST);
		renderState.vao.setVao(resolveVao);
		renderState.apply();

		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, destinationTexture, 0);
		glDrawBuffer(GL_COLOR_ATTACHMENT0);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	@Override
	public void destroy() {
		super.destroy();
		if (resolveFBO != 0)
			glDeleteFramebuffers(resolveFBO);
		resolveFBO = 0;
	}

	public static class MultiSample extends OitDepthMergeShaderProgram {
		private final UniformTexture uniFirstLayerDepthMS = addUniformTexture("firstLayerDepthMS");
		private final UniformTexture uniFarthestLayerDepthMS = addUniformTexture("farthestLayerDepthMS");

		public MultiSample() {
			super();
		}

		public void merge(
			RenderState renderState,
			int resolveVao,
			int firstLayerTextureUnit,
			int farthestLayerTextureUnit,
			int firstLayerDepthMS,
			int farthestLayerDepthMS,
			int destinationTexture
		) {
			glActiveTexture(firstLayerTextureUnit);
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, firstLayerDepthMS);
			glActiveTexture(farthestLayerTextureUnit);
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, farthestLayerDepthMS);

			use();
			uniFirstLayerDepthMS.set(firstLayerTextureUnit);
			uniFarthestLayerDepthMS.set(farthestLayerTextureUnit);

			resolve(renderState, resolveVao, destinationTexture);
		}
	}
}
