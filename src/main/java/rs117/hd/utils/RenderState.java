package rs117.hd.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import rs117.hd.opengl.GLState;
import rs117.hd.opengl.shader.ShaderProgram;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;

public final class RenderState {
	private final List<GLState> states = new ArrayList<>();

	public final GLBindFramebuffer framebuffer = addState(GLBindFramebuffer::new);
	public final GLFramebufferTextureLayer framebufferTextureLayer = addState(GLFramebufferTextureLayer::new);
	public final GLDrawBuffer drawBuffer = addState(GLDrawBuffer::new);
	public final GLShaderProgram program = addState(GLShaderProgram::new);
	public final GLViewport viewport = addState(GLViewport::new);
	public final GLBindVAO vao = addState(GLBindVAO::new);
	public final GLBindEBO ebo = addState(() -> new GLBindEBO(vao));
	public final GLBindIDO ido = addState(GLBindIDO::new);
	public final GLBindUBO ubo = addState(GLBindUBO::new);
	public final GLDepthMask depthMask = addState(GLDepthMask::new);
	public final GLDepthFunc depthFunc = addState(GLDepthFunc::new);
	public final GLColorMask colorMask = addState(GLColorMask::new);
	public final GLBlendFunc blendFunc = addState(GLBlendFunc::new);
	public final GLStencilMask stencilMask = addState(GLStencilMask::new);
	public final GLStencilFunc stencilFunc = addState(GLStencilFunc::new);
	public final GLStencilOp stencilOp = addState(GLStencilOp::new);
	public final GLEnable enable = addState(GLEnable::new);
	public final GLDisable disable = addState(GLDisable::new);

	public void apply() {
		for (GLState state : states)
			state.apply();
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

	public static final class GLBindFramebuffer extends GLState.IntArray {
		private GLBindFramebuffer() {
			super(2);
		}

		@Override
		protected void applyValues(int[] values) { glBindFramebuffer(values[0], values[1]); }
	}

	public static final class GLFramebufferTextureLayer extends GLState.IntArray {
		private GLFramebufferTextureLayer() { super(5); }

		@Override
		protected void applyValues(int[] values) {
			glFramebufferTextureLayer(values[0], values[1], values[2], values[3], values[4]);
		}
	}

	public static final class GLViewport extends GLState.IntArray {
		private GLViewport() {
			super(4);
		}

		@Override
		protected void applyValues(int[] values) { glViewport(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLShaderProgram extends GLState.Object<ShaderProgram> {
		@Override
		protected void applyValue(ShaderProgram program) { program.use(); }
	}

	public static final class GLDrawBuffer extends GLState.Int {
		@Override
		protected void applyValue(int buf) { glDrawBuffer(buf); }
	}

	public static final class GLBindVAO extends GLState.Int {
		@Override
		protected void applyValue(int vao) { glBindVertexArray(vao); }
	}

	@RequiredArgsConstructor
	public static final class GLBindEBO extends GLState.Int {
		private final GLBindVAO vaoBinding;

		@Override
		protected void applyValue(int ebo) {
			if (vaoBinding.getValue() != 0) {
				vaoBinding.apply();
				glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
			}
		}
	}

	public static final class GLBindIDO extends GLState.Int {
		@Override
		protected void applyValue(int ebo) { glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ebo); }
	}

	public static final class GLBindUBO extends GLState.Int {
		@Override
		protected void applyValue(int ubo) { glBindBuffer(GL_UNIFORM_BUFFER, ubo); }
	}

	public static final class GLDepthMask extends GLState.Bool {
		@Override
		protected void applyValue(boolean enabled) { glDepthMask(enabled); }
	}

	public static final class GLDepthFunc extends GLState.Int {
		@Override
		protected void applyValue(int func) { glDepthFunc(func); }
	}

	public static final class GLBlendFunc extends GLState.IntArray {
		private GLBlendFunc() {
			super(4);
		}

		@Override
		protected void applyValues(int[] values) { glBlendFuncSeparate(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLStencilMask extends GLState.Int {
		@Override
		protected void applyValue(int value) { glStencilMask(value); }
	}

	public static final class GLStencilFunc extends GLState.IntArray {
		private GLStencilFunc() {
			super(3);
		}

		@Override
		protected void applyValues(int[] values) { glStencilFunc(values[0], values[1], values[2]); }
	}

	public static final class GLStencilOp extends GLState.IntArray {
		private GLStencilOp() {
			super(3);
		}

		@Override
		protected void applyValues(int[] values) { glStencilOp(values[0], values[1], values[2]); }
	}

	public static final class GLColorMask extends GLState.BoolArray {
		private GLColorMask() {
			super(4);
		}

		@Override
		protected void applyValues(boolean[] values) { glColorMask(values[0], values[1], values[2], values[3]); }
	}

	public final class GLEnable extends GLState.IntSet {
		@Override
		protected void applyTarget(int target) { glEnable(target); }

		public void set(int target) {
			add(target);
			disable.remove(target);
		}
	}

	public final class GLDisable extends GLState.IntSet {
		@Override
		protected void applyTarget(int target) { glDisable(target); }

		public void set(int target) {
			add(target);
			enable.remove(target);
		}
	}
}
