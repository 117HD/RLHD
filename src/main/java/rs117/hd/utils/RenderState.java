package rs117.hd.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import rs117.hd.opengl.GLState;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.utils.collections.Int2IntHashMap;
import rs117.hd.utils.collections.IntHashSet;

import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static rs117.hd.HdPlugin.GL_CAPS;
import static rs117.hd.HdPlugin.checkGLErrors;

public final class RenderState {
	private static final IntHashSet DEFAULT_ENABLED_CAPABILITIES = new IntHashSet();

	static {
		// SEE: https://registry.khronos.org/OpenGL-Refpages/gl4/html/glEnable.xhtml
		// These two Capabilities are the only ones enabled by default
		DEFAULT_ENABLED_CAPABILITIES.add(GL_DITHER);
		DEFAULT_ENABLED_CAPABILITIES.add(GL_MULTISAMPLE);
	}

	private final List<GLState> states = new ArrayList<>();
	private final IntHashSet trackedCapabilities = new IntHashSet();

	public final GLFramebuffer framebuffer = addState(() -> new GLFramebuffer(GL_FRAMEBUFFER));
	public final GLFramebuffer drawFramebuffer = addState(() -> new GLFramebuffer(GL_DRAW_FRAMEBUFFER));
	public final GLFramebuffer readFramebuffer = addState(() -> new GLFramebuffer(GL_READ_FRAMEBUFFER));
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
	public final GLPolygonMode polygonMode = addState(GLPolygonMode::new);
	public final GLClearDepth clearDepth = addState(GLClearDepth::new);
	public final GLClearColor clearColor = addState(GLClearColor::new);
	public final GLDisable disable = addState(GLDisable::new);
	public final GLEnable enable = addState(GLEnable::new);
	public final GLTexture texture = addState(GLTexture::new);

	public void apply() {
		for (GLState state : states) {
			state.apply();
			checkGLErrors(() -> "Failed to apply state: " + state.getClass().getName());
		}
	}

	public void reset() {
		for (GLState state : states)
			state.reset();

		for (int capability : trackedCapabilities) {
			if (DEFAULT_ENABLED_CAPABILITIES.contains(capability))
				enable.set(capability);
			else
				disable.set(capability);
		}
	}

	public void toggle(int target, boolean enabled) {
		if (enabled)
			enable.set(target);
		else
			disable.set(target);
	}

	private <T extends GLState> T addState(Supplier<T> supplier) {
		T state = supplier.get();
		states.add(state);
		return state;
	}

	public final class GLFramebuffer extends GLState.Int {
		private final int target;

		private GLFramebuffer(int target) {
			super(0);
			this.target = target;
		}

		private boolean hasAppliedNonDefaultValue() {
			return hasApplied && getValue() != 0;
		}

		private boolean hasAppliedValue() {
			return hasApplied;
		}

		@Override
		public void set(int framebuffer) {
			super.set(framebuffer);
			if (target == GL_FRAMEBUFFER) {
				drawFramebuffer.set(framebuffer);
				readFramebuffer.set(framebuffer);
			}
		}

		@Override
		protected void internalApply() {
			applyValue(getValue());
		}

		@Override
		protected void applyValue(int framebuffer) {
			glBindFramebuffer(target, framebuffer);
		}
	}

	public final class GLFramebufferTextureLayer extends GLState.IntArray {
		private GLFramebufferTextureLayer() { super(5); }

		@Override
		public void reset() {
			super.reset();
			hasValue = false;
		}

		@Override
		protected void applyValues(int[] values) {
			glFramebufferTextureLayer(values[0], values[1], values[2], values[3], values[4]);
		}

		@Override
		protected boolean canApply() {
			return drawFramebuffer.hasAppliedNonDefaultValue();
		}
	}

	public static final class GLViewport extends GLState.IntArray {
		private boolean hasCapturedDefaultValue;

		private GLViewport() {
			super(4);
		}

		@Override
		public void set(int... values) {
			captureDefaultValue();
			super.set(values);
		}

		private void captureDefaultValue() {
			if (hasCapturedDefaultValue)
				return;
			int[] defaultValue = new int[4];
			glGetIntegerv(GL_VIEWPORT, defaultValue);
			setDefaultValue(defaultValue);
			hasCapturedDefaultValue = true;
		}

		@Override
		public void reset() {
			captureDefaultValue();
			super.reset();
		}

		@Override
		protected void applyValues(int[] values) { glViewport(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLShaderProgram extends GLState.Object<ShaderProgram> {
		@Override
		protected void applyValue(ShaderProgram program) {
			if (program == null)
				glUseProgram(0);
			else
				program.use();
		}
	}

	public final class GLDrawBuffer extends GLState {
		private int value;

		public void set(int value) {
			this.value = value;
			hasValue = true;
		}

		@Override
		protected void internalApply() {
			glDrawBuffer(value);
		}

		@Override
		protected boolean canApply() {
			return drawFramebuffer.hasAppliedValue();
		}
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

		public GLTexture() {
			Arrays.fill(desired, UNKNOWN);
			clearCache();
		}

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
			if (activeUnit != UNKNOWN && activeUnit != 0) {
				glActiveTexture(GL_TEXTURE0);
				activeUnit = 0;
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
			int dirtyUnits = 0;
			for (int unit = 0; unit < MAX_UNITS; unit++) {
				final int base = unit * MAX_TARGETS;
				for (int target = 0; target < targetCount; target++) {
					if (desired[base + target] != UNKNOWN) {
						desired[base + target] = 0;
						dirtyUnits |= 1 << unit;
					}
				}
			}
			this.dirtyUnits = dirtyUnits;
			clearCache();
			hasValue = dirtyUnits != 0;
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

		@Override
		public void reset() {
			super.reset();
			invalidate();
			setVaoAndEbo(0, 0);
		}
	}

	public static final class GLIdo extends GLState.Int {
		private GLIdo() { super(0); }

		@Override
		protected void applyValue(int ebo) { glBindBuffer(GL_DRAW_INDIRECT_BUFFER, ebo); }
	}

	public static final class GLUbo extends GLState.Int {
		private GLUbo() { super(0); }

		@Override
		protected void applyValue(int ubo) { glBindBuffer(GL_UNIFORM_BUFFER, ubo); }
	}

	public static final class GLDepthMask extends GLState.Bool {
		private GLDepthMask() { super(true); }

		@Override
		protected void applyValue(boolean enabled) { glDepthMask(enabled); }
	}

	public static final class GLDepthFunc extends GLState.Int {
		private GLDepthFunc() { super(GL_LESS); }

		@Override
		protected void applyValue(int func) { glDepthFunc(func); }
	}

	public static final class GLCullFace extends GLState.Int {
		private GLCullFace() { super(GL_BACK); }

		@Override
		protected void applyValue(int mode) { glCullFace(mode); }
	}

	public static final class GLPolygonMode extends GLState.IntArray {
		private GLPolygonMode() { super(2, GL_FRONT_AND_BACK, GL_FILL); }

		@Override
		protected void applyValues(int[] values) { glPolygonMode(values[0], values[1]); }
	}

	public static final class GLClearDepth extends GLState.Float {
		private GLClearDepth() { super(1); }

		@Override
		protected void applyValue(float depth) { glClearDepth(depth); }
	}

	public static final class GLClearColor extends GLState.FloatArray {
		private GLClearColor() { super(4, 0, 0, 0, 0); }

		@Override
		protected void applyValues(float[] color) { glClearColor(color[0], color[1], color[2], color[3]); }
	}

	public static final class GLBlendFunc extends GLState.IntArray {
		private GLBlendFunc() {
			super(4, GL_ONE, GL_ZERO, GL_ONE, GL_ZERO);
		}

		@Override
		protected void applyValues(int[] values) { glBlendFuncSeparate(values[0], values[1], values[2], values[3]); }
	}

	public static final class GLColorMask extends GLState.BoolArray {
		private GLColorMask() {
			super(4, true, true, true, true);
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
			trackedCapabilities.add(target);
		}
	}

	public final class GLDisable extends GLState.IntSet {
		@Override
		protected void applyTarget(int target) { glDisable(target); }

		public void set(int target) {
			add(target);
			enable.remove(target);
			trackedCapabilities.add(target);
		}
	}
}
