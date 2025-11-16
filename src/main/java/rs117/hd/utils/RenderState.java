package rs117.hd.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import rs117.hd.opengl.GLState;
import rs117.hd.opengl.shader.ShaderProgram;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;

public final class RenderState {
	private final List<GLState<RenderState>> states = new ArrayList<>();

	public final GLBindFramebuffer framebuffer = addState(GLBindFramebuffer::new);
	public final GLFramebufferTextureLayer framebufferTextureLayer = addState(GLFramebufferTextureLayer::new);
	public final GLDrawBuffer drawBuffer = addState(GLDrawBuffer::new);
	public final GLShaderProgram program = addState(GLShaderProgram::new);
	public final GLViewport viewport = addState(GLViewport::new);
	public final GLBindVAO vao = addState(GLBindVAO::new);
	public final GLBindEBO ebo = addState(GLBindEBO::new);
	public final GLBindIDO ido = addState(GLBindIDO::new);
	public final GLBindUBO ubo = addState(GLBindUBO::new);
	public final GLPolygonOffset polygonOffset = addState(GLPolygonOffset::new);
	public final GLDepthMask depthMask = addState(GLDepthMask::new);
	public final GLDepthFunc depthFunc = addState(GLDepthFunc::new);
	public final GLColorMask colorMask = addState(GLColorMask::new);
	public final GLBlendFunc blendFunc = addState(GLBlendFunc::new);
	public final GLEnable enable = addState(GLEnable::new);
	public final GLDisable disable = addState(GLDisable::new);

	public void apply() {
		for (GLState<RenderState> state : states)
			state.apply();
	}

	public void reset() {
		for (GLState<RenderState> state : states)
			state.reset();
	}

	private <T extends GLState<RenderState>> T addState(Supplier<T> supplier) {
		T state = supplier.get();
		state.owner = this;
		states.add(state);
		return state;
	}

	public static final class GLBindFramebuffer extends GLState.PrimitiveArrayState<RenderState, Integer> {
		private GLBindFramebuffer() {
			super(() -> new Integer[2]);
		}

		@Override
		protected void applyValues(Integer[] values) { glBindFramebuffer(values[0], values[1]); }
	}

	public static final class GLFramebufferTextureLayer extends GLState.PrimitiveArrayState<RenderState, Integer> {
		private GLFramebufferTextureLayer() {
			super(() -> new Integer[5]);
		}

		@Override
		protected void applyValues(Integer[] values) {
			glFramebufferTextureLayer(values[0], values[1], values[2], values[3], values[4]);
		}
	}

	public static final class GLViewport extends GLState.PrimitiveArrayState<RenderState, Integer> {
		private GLViewport() {
			super(() -> new Integer[4]);
		}

		@Override
		protected void applyValues(Integer[] values) { glViewport(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLShaderProgram extends GLState.SingleState<RenderState, ShaderProgram> {
		@Override
		protected void applyValue(ShaderProgram program) { program.use(); }
	}

	public static final class GLDrawBuffer extends GLState.SingleState<RenderState, Integer> {
		@Override
		protected void applyValue(Integer buf) { glDrawBuffer(buf); }
	}

	public static final class GLBindVAO extends GLState.SingleState<RenderState, Integer> {
		@Override
		protected void applyValue(Integer vao) { glBindVertexArray(vao); }
	}

	public static final class GLBindEBO extends GLState.SingleState<RenderState, Integer> {
		@Override
		protected void applyValue(Integer ebo) { glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo); }
	}

	public static final class GLBindIDO extends GLState.SingleState<RenderState, Integer> {
		@Override
		protected void applyValue(Integer ebo) { glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ebo); }
	}

	public static final class GLBindUBO extends GLState.SingleState<RenderState, Integer> {
		@Override
		protected void applyValue(Integer ubo) { glBindBuffer(GL_UNIFORM_BUFFER, ubo); }
	}

	public static final class GLPolygonOffset extends GLState.PrimitiveArrayState<RenderState, Float> {
		private GLPolygonOffset() {
			super(() -> new Float[2]);
		}
		@Override
		protected void applyValues(Float[] values) { glPolygonOffset(values[0], values[1]); }
	}

	public static final class GLDepthMask extends GLState.SingleState<RenderState, Boolean> {
		@Override
		protected void applyValue(Boolean enabled) { glDepthMask(enabled); }
	}

	public static final class GLDepthFunc extends GLState.SingleState<RenderState, Integer> {
		@Override
		protected void applyValue(Integer func) { glDepthFunc(func); }
	}

	public static final class GLBlendFunc extends GLState.PrimitiveArrayState<RenderState, Integer> {
		private GLBlendFunc() {
			super(() -> new Integer[4]);
		}

		@Override
		protected void applyValues(Integer[] values) { glBlendFuncSeparate(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLColorMask extends GLState.PrimitiveArrayState<RenderState, Boolean> {
		private GLColorMask() {
			super(() -> new Boolean[4]);
		}

		@Override
		protected void applyValues(Boolean[] values) { glColorMask(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLEnable extends GLState.GLFlagSetState<RenderState> {
		@Override
		protected void applyTarget(int target) { glEnable(target); }

		public void set(int target) {
			add(target);
			owner.disable.remove(target);
		}
	}

	public static final class GLDisable extends GLState.GLFlagSetState<RenderState> {
		@Override
		protected void applyTarget(int target) { glDisable(target); }

		public void set(int target) {
			add(target);
			owner.enable.remove(target);
		}
	}
}
