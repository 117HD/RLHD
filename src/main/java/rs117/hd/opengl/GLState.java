package rs117.hd.opengl;

import java.util.Arrays;
import java.util.Objects;
import lombok.Getter;
import rs117.hd.utils.collections.IntHashSet;

public abstract class GLState {
	protected boolean hasValue;
	protected boolean hasApplied;

	public void reset() {
		hasValue = hasApplied = false;
	}

	public void invalidate() {
		hasValue = true;
		hasApplied = false;
	}

	public void apply() {
		if (hasValue && canApply()) {
			internalApply();
			hasValue = false;
			hasApplied = true;
		}
	}

	protected boolean canApply() {
		return true;
	}

	protected void internalApply() {}

	public abstract static class Bool extends GLState {
		@Getter
		private boolean value;
		private boolean appliedValue;
		private final boolean defaultValue;

		protected Bool() {
			this(false);
		}

		protected Bool(boolean defaultValue) {
			this.defaultValue = defaultValue;
		}

		public final void set(boolean v) {
			hasValue = true;
			value = v;
		}

		@Override
		public void reset() {
			super.reset();
			set(defaultValue);
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || value != appliedValue) {
				applyValue(value);
				appliedValue = value;
			}
		}

		protected abstract void applyValue(boolean value);
	}

	public abstract static class Int extends GLState {
		@Getter
		private int value;
		private int appliedValue;
		private final int defaultValue;

		protected Int() {
			this(0);
		}

		protected Int(int defaultValue) {
			this.defaultValue = defaultValue;
		}

		public void set(int v) {
			hasValue = true;
			value = v;
		}

		@Override
		public void reset() {
			super.reset();
			set(defaultValue);
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || value != appliedValue) {
				applyValue(value);
				appliedValue = value;
			}
		}

		protected abstract void applyValue(int value);
	}

	public abstract static class Float extends GLState {
		@Getter
		private float value;
		private float appliedValue;
		private final float defaultValue;

		protected Float() {
			this(0);
		}

		protected Float(float defaultValue) {
			this.defaultValue = defaultValue;
		}

		public final void set(float value) {
			hasValue = true;
			this.value = value;
		}

		@Override
		public void reset() {
			super.reset();
			set(defaultValue);
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || value != appliedValue) {
				applyValue(value);
				appliedValue = value;
			}
		}

		protected abstract void applyValue(float value);
	}

	public abstract static class Object<T> extends GLState {
		@Getter
		private T value;
		private T appliedValue;

		public final void set(T v) {
			hasValue = true;
			value = v;
		}

		@Override
		public void reset() {
			super.reset();
			set(null);
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || !Objects.equals(value, appliedValue)) {
				applyValue(value);
				appliedValue = value;
			}
		}

		protected abstract void applyValue(T value);
	}

	public abstract static class IntArray extends GLState {
		@Getter
		private final int[] value;
		protected final int[] appliedValue;
		private final int[] defaultValue;

		protected IntArray(int size) {
			this(size, new int[size]);
		}

		protected IntArray(int size, int... defaultValue) {
			if (defaultValue.length != size)
				throw new IllegalArgumentException("Default value length must match array size");
			value = new int[size];
			appliedValue = new int[size];
			this.defaultValue = Arrays.copyOf(defaultValue, size);
		}

		protected final void setDefaultValue(int... values) {
			if (values.length != defaultValue.length)
				throw new IllegalArgumentException("Default value length must match array size");
			System.arraycopy(values, 0, defaultValue, 0, values.length);
		}

		public void set(int... v) {
			hasValue = true;
			System.arraycopy(v, 0, value, 0, v.length);
		}

		@Override
		public void reset() {
			super.reset();
			set(defaultValue);
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || !Arrays.equals(value, appliedValue)) {
				applyValues(value);
				System.arraycopy(value, 0, appliedValue, 0, value.length);
			}
		}

		protected abstract void applyValues(int[] values);
	}

	public abstract static class BoolArray extends GLState {
		@Getter
		private final boolean[] value;
		private final boolean[] appliedValue;
		private final boolean[] defaultValue;

		protected BoolArray(int size) {
			this(size, new boolean[size]);
		}

		protected BoolArray(int size, boolean... defaultValue) {
			if (defaultValue.length != size)
				throw new IllegalArgumentException("Default value length must match array size");
			value = new boolean[size];
			appliedValue = new boolean[size];
			this.defaultValue = Arrays.copyOf(defaultValue, size);
		}

		public final void set(boolean... v) {
			hasValue = true;
			System.arraycopy(v, 0, value, 0, v.length);
		}

		@Override
		public void reset() {
			super.reset();
			set(defaultValue);
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || !Arrays.equals(value, appliedValue)) {
				applyValues(value);
				System.arraycopy(value, 0, appliedValue, 0, value.length);
			}
		}

		protected abstract void applyValues(boolean[] values);
	}

	public abstract static class FloatArray extends GLState {
		@Getter
		private final float[] value;
		private final float[] appliedValue;
		private final float[] defaultValue;

		protected FloatArray(int size, float... defaultValue) {
			if (defaultValue.length != size)
				throw new IllegalArgumentException("Default value length must match array size");
			value = new float[size];
			appliedValue = new float[size];
			this.defaultValue = Arrays.copyOf(defaultValue, size);
		}

		public final void set(float... values) {
			hasValue = true;
			System.arraycopy(values, 0, value, 0, values.length);
		}

		@Override
		public void reset() {
			super.reset();
			set(defaultValue);
		}

		@Override
		protected void internalApply() {
			if (!hasApplied || !Arrays.equals(value, appliedValue)) {
				applyValues(value);
				System.arraycopy(value, 0, appliedValue, 0, value.length);
			}
		}

		protected abstract void applyValues(float[] values);
	}

	public abstract static class IntSet extends GLState {
		private final IntHashSet targets = new IntHashSet();

		public void add(int target) {
			hasValue = true;
			targets.add(target);
		}

		public void remove(int target) {
			targets.remove(target);
			hasApplied = !targets.isEmpty();
		}

		@Override
		protected void internalApply() {
			for (int t : targets)
				applyTarget(t);
		}

		@Override
		public void reset() {
			super.reset();
			targets.clear();
		}

		protected abstract void applyTarget(int target);
	}
}
