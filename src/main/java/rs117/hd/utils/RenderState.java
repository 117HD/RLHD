package rs117.hd.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import rs117.hd.opengl.GLState;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.utils.collections.Int2IntHashMap;

import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
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
	public final GLColorMask colorMask = addState(GLColorMask::new);
	public final GLBlendFunc blendFunc = addState(GLBlendFunc::new);
	public final GLCullFace cullFace = addState(GLCullFace::new);
	public final GLEnable enable = addState(GLEnable::new);
	public final GLDisable disable = addState(GLDisable::new);
	public final GLTexture texture = addState(GLTexture::new);

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

	public static final class GLTexture extends GLState {
		private static final int MAX_UNITS = 32;
		private static final int MAX_TARGETS = 8;
		private static final int UNKNOWN = -1;

		private final int[] targets = new int[MAX_TARGETS];
		private int targetCount;

		// Slot = unit * MAX_TARGETS + targetIndex
		private final int[] bound = new int[MAX_UNITS * MAX_TARGETS];   // what GL has bound, or UNKNOWN
		private final int[] desired = new int[MAX_UNITS * MAX_TARGETS]; // last requested, or UNKNOWN for none
		private int dirtyUnits; // bit per unit with a possibly pending bind
		private int activeUnit = UNKNOWN;

		public GLTexture() { clearCache(); }

		public void set(int target, int texUnit, int texId) {
			int unit = texUnit - GL_TEXTURE0;
			assert unit >= 0 && unit < MAX_UNITS;

			int slot = unit * MAX_TARGETS + targetIndex(target);
			desired[slot] = texId;
			if (bound[slot] != texId) {
				dirtyUnits |= 1 << unit;
				hasValue = true;
			}
		}

		private int targetIndex(int target) {
			for (int i = 0; i < targetCount; i++)
				if (targets[i] == target)
					return i;
			if (targetCount == MAX_TARGETS)
				throw new IllegalStateException("Too many distinct texture targets, increase MAX_TARGETS");
			targets[targetCount] = target;
			return targetCount++;
		}

		@Override
		protected void internalApply() {
			int mask = dirtyUnits;
			dirtyUnits = 0;
			while (mask != 0) {
				final int unit = Integer.numberOfTrailingZeros(mask);
				mask &= mask - 1;

				final int base = unit * MAX_TARGETS;
				for (int t = 0; t < targetCount; t++) {
					int texId = desired[base + t];
					// UNKNOWN means never requested, and equal means a retarget back to what is bound
					if (texId == UNKNOWN || texId == bound[base + t])
						continue;

					if (GL_CAPS.GL_ARB_direct_state_access && texId != 0) {
						glBindTextureUnit(unit, texId);
					} else {
						if (unit != activeUnit) {
							glActiveTexture(GL_TEXTURE0 + unit);
							activeUnit = unit;
						}
						glBindTexture(targets[t], texId);
					}
					bound[base + t] = texId;
				}
			}
		}

		private void clearCache() {
			Arrays.fill(bound, UNKNOWN);
			activeUnit = UNKNOWN;
		}

		@Override
		public void invalidate() {
			super.invalidate();
			clearCache();
			hasValue = dirtyUnits != 0;
		}

		@Override
		public void reset() {
			super.reset();
			clearCache();
			Arrays.fill(desired, UNKNOWN);
			dirtyUnits = 0;
		}
	}

	public static final class GLVao extends GLState {
		int vao, ebo;
		int appliedVao;

		private final Int2IntHashMap vaoSlots = new Int2IntHashMap();
		private int[] slotEbo = new int[16];
		private int slotCount;
		private int boundSlot = -1; // slot of appliedVao, -1 if not looked up yet

		public void setVao(int vao) { setVaoAndEbo(vao, 0); }

		public void setVaoAndEbo(int vao, int ebo) {
			this.vao = vao;
			this.ebo = ebo;
			hasValue = true;
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || vao != appliedVao) {
				glBindVertexArray(vao);
				appliedVao = vao;
				boundSlot = -1;
			}

			if (ebo == 0 || vao == 0 || (boundSlot != -1 && slotEbo[boundSlot] == ebo))
				return;

			if (boundSlot == -1) {
				// VAO changed since the last lookup
				boundSlot = vaoSlots.getOrDefault(vao, -1);
				if (boundSlot == -1) {
					// First time this VAO is seen since the last invalidate: its EBO is unknown, fall through to bind
					boundSlot = slotCount++;
					if (boundSlot >= slotEbo.length)
						slotEbo = Arrays.copyOf(slotEbo, slotEbo.length * 2);
					vaoSlots.put(vao, boundSlot);
				} else if (slotEbo[boundSlot] == ebo) {
					return; // known VAO that already has this EBO
				}
			}

			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
			slotEbo[boundSlot] = ebo;
		}

		@Override
		public void invalidate() {
			super.invalidate();
			vaoSlots.clear();
			slotCount = 0;
			boundSlot = -1;
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

	public static final class GLCullFace extends GLState.Int {
		@Override
		protected void applyValue(int mode) { glCullFace(mode); }
	}

	public static final class GLBlendFunc extends GLState.IntArray {
		private GLBlendFunc() {
			super(4);
		}

		@Override
		protected void applyValues(int[] values) { glBlendFuncSeparate(values[0], values[1], values[2], values[3]); }
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
