package rs117.hd.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import rs117.hd.opengl.GLState;
import rs117.hd.opengl.GLVao;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.utils.buffer.GLBuffer;

import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;

public final class RenderState {
	private final List<GLState> states = new ArrayList<>();

	public final GLBindFramebuffer framebuffer = addState(GLBindFramebuffer::new);
	public final GLBindDrawFramebuffer drawFramebuffer = addState(GLBindDrawFramebuffer::new);
	public final GLBindReadFramebuffer readFramebuffer = addState(GLBindReadFramebuffer::new);
	public final GLFramebufferTextureLayer framebufferTextureLayer = addState(GLFramebufferTextureLayer::new);
	public final GLDrawBuffer drawBuffer = addState(GLDrawBuffer::new);
	public final GLShaderProgram program = addState(GLShaderProgram::new);
	public final GLViewport viewport = addState(GLViewport::new);
	public final GLBindVAO vao = addState(GLBindVAO::new);
	public final GLBindIDO ido = addState(GLBindIDO::new);
	public final GLBindUBO ubo = addState(GLBindUBO::new);
	public final GLDepthMask depthMask = addState(GLDepthMask::new);
	public final GLDepthFunc depthFunc = addState(GLDepthFunc::new);
	public final GLColorMask colorMask = addState(GLColorMask::new);
	public final GLBlendFunc blendFunc = addState(GLBlendFunc::new);
	public final GLToggle blend = new GLToggle(GL_BLEND, false);
	public final GLToggle cullFace = new GLToggle(GL_CULL_FACE, true);
	public final GLToggle depthTest = new GLToggle(GL_DEPTH_TEST, false);
	public final GLToggle multisample = new GLToggle(GL_MULTISAMPLE, false);

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

	private <T extends GLState> T addState(Supplier<T> supplier) {
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
			if(values[0] == 0)
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
			if(program != null) {
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
		public void setDefault() {  }
	}

	public static final class GLBindVAO extends GLState.Object<GLVao> {
		@Override
		protected void applyValue(GLVao vao) {
			if(getAppliedValue() != null)
				getAppliedValue().unbind();

			if(vao != null)
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
		public void setDefault() { set(false); }
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
			if(value) {
				glEnable(target);
			} else {
				glDisable(target);
			}
		}

		@Override
		public void setDefault() { set(defaultState); }
	}
}
