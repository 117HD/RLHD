package rs117.hd.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.lwjgl.opengl.*;
import rs117.hd.opengl.GLState;
import rs117.hd.opengl.shader.ShaderProgram;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL40C.glBlendEquationi;
import static rs117.hd.HdPlugin.GL_CAPS;

public final class RenderState {
	private final List<GLState> states = new ArrayList<>();

	public final GLFramebuffer framebuffer = addState(GLFramebuffer::new);
	public final GLFramebufferTextureLayer framebufferTextureLayer = addState(GLFramebufferTextureLayer::new);
	public final GLDrawBuffer drawBuffer = addState(GLDrawBuffer::new);
	public final GLShaderProgram program = addState(GLShaderProgram::new);
	public final GLViewport viewport = addState(GLViewport::new);
	public final GLVao vao = addState(GLVao::new);
	public final GLIdo ido = addState(GLIdo::new);
	public final GLUbo ubo = addState(GLUbo::new);
	public final GLDepthMask depthMask = addState(GLDepthMask::new);
	public final GLDepthFunc depthFunc = addState(GLDepthFunc::new);
	public final GLSampleShading sampleShading = addState(GLSampleShading::new);
	public final GLColorMask colorMask = addState(GLColorMask::new);
	public final GLColorMaski colorMaski = addState(GLColorMaski::new);
	public final GLBlendFunc blendFunc = addState(GLBlendFunc::new);
	public final GLBlendEquation blendEquation = addState(GLBlendEquation::new);
	public final GLBlendFunci blendFunci = addState(GLBlendFunci::new);
	public final GLBlendEquationi blendEquationi = addState(GLBlendEquationi::new);
	public final GLEnable enable = addState(GLEnable::new);
	public final GLDisable disable = addState(GLDisable::new);

	public void toggle(int capability, boolean enabled) {
		if (enabled)
			enable.set(capability);
		else
			disable.set(capability);
	}

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

	public static final class GLFramebuffer extends GLState.IntArray {
		private GLFramebuffer() {
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

	public static final class GLVao extends GLState {
		int vao, ebo;
		int appliedVao, appliedEbo;

		public void setVao(int vao) {
			setVaoAndEbo(vao, 0);
		}

		public void setVaoAndEbo(int vao, int ebo) {
			this.vao = vao;
			this.ebo = ebo;
			hasValue = true;
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || vao != appliedVao)
				glBindVertexArray(vao);
			if (ebo != 0 && (!hasApplied || ebo != appliedEbo))
				glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
		}
	}

	public static final class GLIdo extends GLState.Int {
		@Override
		protected void applyValue(int ebo) { glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ebo); }
	}

	public static final class GLUbo extends GLState.Int {
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

	public static final class GLSampleShading extends GLState.Float {
		@Override
		protected void applyValue(float blend) { ARBSampleShading.glMinSampleShadingARB(1.0f); }
	}

	public static final class GLBlendFunc extends GLState.IntArray {
		private GLBlendFunc() {
			super(4);
		}

		@Override
		protected void applyValues(int[] values) { glBlendFuncSeparate(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLBlendEquation extends GLState.Int {
		@Override
		protected void applyValue(int equation) { glBlendEquation(equation); }
	}

	public static final class GLBlendFunci extends GLState.IndexedIntArray {
		GLBlendFunci() { super(4); }

		public void set(int attachment, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
			setValue(attachment, srcRGB, dstRGB, srcAlpha, dstAlpha);
		}

		@Override
		protected void applyValue(int attachment, int[] values, int offset) {
			assert GL_CAPS.GL_ARB_draw_buffers_blend : "GL_ARB_draw_buffers_blend required";
			GL40.glBlendFuncSeparatei(
				attachment,
				values[offset], values[offset + 1], values[offset + 2], values[offset + 3]
			);
		}
	}

	public static final class GLBlendEquationi extends GLState.IndexedInt {

		GLBlendEquationi() { super(8); }

		public void set(int attachment, int equation) {
			setValue(attachment, equation);
		}

		@Override
		protected void applyValue(int attachment, int value) {
			assert GL_CAPS.GL_ARB_draw_buffers_blend : "GL_ARB_draw_buffers_blend required";
			glBlendEquationi(attachment, value);
		}
	}

	public static final class GLColorMask extends GLState.BoolArray {
		private GLColorMask() {
			super(4);
		}

		@Override
		protected void applyValues(boolean[] values) { glColorMask(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLColorMaski extends GLState.IndexedInt {

		GLColorMaski() { super(8); }

		public void set(int attachment, boolean red, boolean green, boolean blue, boolean alpha) {
			setValue(
				attachment,
				(red ? 1 : 0) |
				(green ? 1 : 0) << 1 |
				(blue ? 1 : 0) << 2 |
				(alpha ? 1 : 0) << 3
			);
		}

		@Override
		protected void applyValue(int attachment, int mask) {
			glColorMaski(
				attachment,
				(mask & 1) != 0,
				(mask & 2) != 0,
				(mask & 4) != 0,
				(mask & 8) != 0
			);
		}
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