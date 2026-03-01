package rs117.hd.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.opengl.GLState;
import rs117.hd.opengl.GLVao;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;

@Slf4j
public final class RenderState {
	private final List<GLState> states = new ArrayList<>();

	public final GLBindFramebuffer framebuffer = createState(GLBindFramebuffer::new);
	public final GLBindDrawFramebuffer drawFramebuffer = createState(GLBindDrawFramebuffer::new);
	public final GLBindReadFramebuffer readFramebuffer = createState(GLBindReadFramebuffer::new);
	public final GLFramebufferTextureLayer framebufferTextureLayer = createState(GLFramebufferTextureLayer::new);
	public final GLDrawBuffer drawBuffer = createState(GLDrawBuffer::new);
	public final GLShaderProgram program = createState(GLShaderProgram::new);
	public final GLViewport viewport = createState(GLViewport::new);
	public final GLBindVAO vao = createState(GLBindVAO::new);
	public final GLBindIDO ido = createState(GLBindIDO::new);
	public final GLBindUBO ubo = createState(GLBindUBO::new);
	public final GLDepthMask depthMask = createState(GLDepthMask::new);
	public final GLDepthFunc depthFunc = createState(GLDepthFunc::new);
	public final GLColorMask colorMask = createState(GLColorMask::new);
	public final GLBlendFunc blendFunc = createState(GLBlendFunc::new);
	public final GLToggle blend = addState(new GLToggle(GL_BLEND, false));
	public final GLToggle cullFace = addState(new GLToggle(GL_CULL_FACE, true));
	public final GLToggle depthTest = addState(new GLToggle(GL_DEPTH_TEST, false));
	public final GLToggle multisample = addState(new GLToggle(GL_MULTISAMPLE, false));

	public void apply() {
		for (GLState state : states)
			state.apply();
	}

	public void setDefaults(){
		for (GLState state : states)
			state.setDefault();
	}

	public void reset() {
		for (GLState state : states)
			state.reset();
	}

	public void printState() {
		StringBuffer sb = new StringBuffer();
		for (GLState state : states)
			state.printState(sb);
		log.debug("GLRenderState:\n{}", sb.toString().trim());
	}

	private <T extends GLState> T addState(T state) {
		states.add(state);
		return state;
	}

	private <T extends GLState> T createState(Supplier<T> supplier) {
		T state = supplier.get();
		states.add(state);
		return state;
	}

	public static class GLBindFramebuffer extends GLState.Int {
		@Override
		protected void applyValue(int value) { glBindFramebuffer(GL_FRAMEBUFFER, value); }

		@Override
		public void setDefault() { set(0); }
	}

	public static class GLBindReadFramebuffer extends GLState.Int {
		@Override
		protected void applyValue(int value) { glBindFramebuffer(GL_READ_FRAMEBUFFER, value); }

		@Override
		public void setDefault() { set(0); }
	}

	public static class GLBindDrawFramebuffer extends GLState.Int {
		@Override
		protected void applyValue(int value) { glBindFramebuffer(GL_DRAW_FRAMEBUFFER, value); }

		@Override
		public void setDefault() { set(0); }
	}

	public static final class GLFramebufferTextureLayer extends GLState.IntArray {
		private GLFramebufferTextureLayer() { super(5); }

		@Override
		protected void applyValues(int[] values) {
			if (values[0] == 0)
				return;
			glFramebufferTextureLayer(values[0], values[1], values[2], values[3], values[4]);
		}

		@Override
		public void setDefault() { set(0, 0, 0, 0, 0); }
	}

	public static final class GLViewport extends GLState.IntArray {
		private GLViewport() {
			super(4);
		}
		@Override
		protected void applyValues(int[] values) { glViewport(values[0], values[1], values[2], values[3]); }
		@Override
		public void setDefault() { set(0, 0, 0, 0); }
	}

	public static final class GLShaderProgram extends GLState.Object<ShaderProgram> {
		@Override
		protected void applyValue(ShaderProgram program) {
			if (program != null) {
				program.use();
			} else {
				glUseProgram(0);
			}
		}
		@Override
		public void setDefault() { set(null); }
	}

	public static final class GLDrawBuffer extends GLState.Int {
		@Override
		protected void applyValue(int buf) { glDrawBuffer(buf); }
		@Override
		public void setDefault() {}
	}

	public static final class GLBindVAO extends GLState.Object<GLVao> {
		@Override
		protected void applyValue(GLVao vao) {
			if (getAppliedValue() != null)
				getAppliedValue().unbind();

			if (vao != null)
				vao.bind();

		}
		@Override
		public void setDefault() { set(null); }
	}

	public static final class GLBindIDO extends GLState.Object<GLBuffer> {
		@Override
		protected void applyValue(GLBuffer ido) { glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ido != null ? ido.id : 0); }
		@Override
		public void setDefault() { set(null); }
	}

	public static final class GLBindUBO extends GLState.Int {
		@Override
		protected void applyValue(int ubo) { glBindBuffer(GL_UNIFORM_BUFFER, ubo); }
		@Override
		public void setDefault() { set(0); }
	}

	public static final class GLDepthMask extends GLState.Bool {
		@Override
		protected void applyValue(boolean enabled) { glDepthMask(enabled); }
		@Override
		public void setDefault() { set(true); }
	}

	public static final class GLDepthFunc extends GLState.Int {
		@Override
		protected void applyValue(int func) { glDepthFunc(func); }
		@Override
		public void setDefault() { set(GL_LESS); }
	}

	public static final class GLBlendFunc extends GLState.IntArray {
		private GLBlendFunc() {
			super(4);
		}

		@Override
		protected void applyValues(int[] values) { glBlendFuncSeparate(values[0], values[1], values[2], values[3]); }
		@Override
		public void setDefault() { set(GL_ONE, GL_ZERO, GL_ONE, GL_ZERO); }
	}

	public static final class GLColorMask extends GLState.BoolArray {
		private GLColorMask() {
			super(4);
		}

		@Override
		protected void applyValues(boolean[] values) { glColorMask(values[0], values[1], values[2], values[3]); }
		@Override
		public void setDefault() { set(true, true, true, true); }
	}

	@RequiredArgsConstructor
	public static final class GLToggle extends GLState.Bool {
		private final int target;
		private final boolean defaultState;

		@Override
		protected void applyValue(boolean value) {
			if (value) {
				glEnable(target);
			} else {
				glDisable(target);
			}
		}

		@Override
		public void setDefault() { set(defaultState); }
	}
}
