package rs117.hd.opengl.shader;

import java.io.IOException;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.opengl.Utils.labelObject;

public class ResolveShaderProgram extends ShaderProgram {
	private int resolveFBO;
	private final String resolveFboLabel;

	protected ResolveShaderProgram(String fragmentShader, String resolveFboLabel) {
		super(t -> t
			.add(GL_VERTEX_SHADER, "ui_vert.glsl")
			.add(GL_FRAGMENT_SHADER, fragmentShader));
		this.resolveFboLabel = resolveFboLabel;
	}

	protected void resolve(
		RenderState renderState,
		int resolveVao,
		int destinationTexture
	) {
		if (resolveFBO == 0) {
			resolveFBO = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, resolveFBO);
			labelObject(GL_FRAMEBUFFER, resolveFBO, resolveFboLabel);
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

	public static class MultiSample extends ResolveShaderProgram {
		private final String resolveFunc;
		private final UniformTexture uniSource = addUniformTexture("sourceMS");
		private final Uniform1i uniSampleCount = addUniform1i("sampleCount");

		protected MultiSample(String resolveFunc) {
			super("msaa_resolve_frag.glsl", "MSAA Resolve FBO");
			this.resolveFunc = resolveFunc;
		}

		public void resolve(
			RenderState renderState,
			int resolveVao,
			int textureUnit,
			int sourceTexture,
			int destinationTexture,
			int sampleCount
		) {
			glActiveTexture(textureUnit);
			glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, sourceTexture);

			use();
			uniSource.set(textureUnit);
			uniSampleCount.set(sampleCount);

			super.resolve(renderState, resolveVao, destinationTexture);
		}

		@Override
		public void compile(ShaderIncludes includes) throws ShaderException, IOException {
			super.compile(includes.copy().define("RESOLVE_FUNC", resolveFunc));
		}
	}

	public static class Min extends MultiSample {
		public Min() { super("min"); }
	}

	public static class Max extends MultiSample {
		public Max() { super("max"); }
	}
}
