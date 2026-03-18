package rs117.hd.opengl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;

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
		if (hasValue) {
			internalApply();
			hasValue = false;
			hasApplied = true;
		}
	}

	protected void internalApply() {}

	public abstract static class Bool extends GLState {
		@Getter
		private boolean value;
		private boolean appliedValue;

		public final void set(boolean v) {
			hasValue = true;
			value = v;
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

		public final void set(int v) {
			hasValue = true;
			value = v;
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

	public abstract static class Object<T> extends GLState {
		@Getter
		private T value;
		private T appliedValue;

		public final void set(T v) {
			hasValue = true;
			value = v;
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

		protected IntArray(int size) {
			value = new int[size];
			appliedValue = new int[size];
		}

		public final void set(int... v) {
			hasValue = true;
			System.arraycopy(v, 0, value, 0, v.length);
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

		protected BoolArray(int size) {
			value = new boolean[size];
			appliedValue = new boolean[size];
		}

		public final void set(boolean... v) {
			hasValue = true;
			System.arraycopy(v, 0, value, 0, v.length);
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

	public abstract static class IntSet extends GLState {
		private final Set<Integer> targets = new HashSet<>();

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
			for (int t : targets) applyTarget(t);
			targets.clear();
		}

		@Override
		public void reset() {
			super.reset();
			targets.clear();
		}

		protected abstract void applyTarget(int target);
	}
}
