package rs117.hd.opengl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public abstract class GLState<T> {
	public T owner;
	protected boolean hasValue;
	protected boolean hasApplied;

	public void reset() {
		hasValue = hasApplied = false;
	}

	public void apply() {
		if (hasValue) {
			internalApply();
			hasValue = false;
			hasApplied = true;
		}
	}

	abstract void internalApply();

	public abstract static class SingleState<T, K> extends GLState<T> {
		private K value;
		private K appliedValue;

		public void set(K v) {
			hasValue = true;
			value = v;
		}

		@Override
		void internalApply() {
			if (!hasApplied || !Objects.equals(value, appliedValue)) {
				applyValue(value);
				appliedValue = value;
			}
		}

		protected abstract void applyValue(K value);

		@Override
		public String toString() {
			return String.format("%s: %s", getClass().getSimpleName(), appliedValue);
		}
	}

	public abstract static class PrimitiveArrayState<T, K> extends GLState<T> {
		private final K[] value;
		private final K[] appliedValue;

		protected PrimitiveArrayState(Supplier<K[]> supplier) {
			this.value = supplier.get();
			this.appliedValue = supplier.get();
		}

		public void set(K... v) {
			hasValue = true;
			System.arraycopy(v, 0, value, 0, v.length);
		}

		@Override
		void internalApply() {
			if (!hasApplied || !Arrays.equals(value, appliedValue)) {
				applyValues(value);
				System.arraycopy(value, 0, appliedValue, 0, value.length);
			}
		}

		protected abstract void applyValues(K[] values);

		@Override
		public String toString() {
			return String.format("%s: %s", getClass().getSimpleName(), Arrays.toString(appliedValue));
		}
	}

	public abstract static class GLFlagSetState<T> extends GLState<T> {
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
		void internalApply() {
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
