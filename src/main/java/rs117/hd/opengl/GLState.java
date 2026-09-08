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

	public abstract static class Float extends GLState {
		@Getter
		private float value;
		private float appliedValue;

		public final void set(float v) {
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

	public abstract static class IndexedInt extends GLState {
		private final int[] values;
		private final int[] appliedValues;
		private int indices;

		@SuppressWarnings("unchecked")
		protected IndexedInt(int size) {
			values = new int[size];
			appliedValues = new int[size];
		}

		protected final void setValue(int index, int value) {
			if (index < 0 || index >= values.length)
				throw new IllegalArgumentException("Invalid index: " + index);

			hasValue = true;
			values[index] = value;
			indices |= 1 << index;
		}

		@Override
		protected final void internalApply() {
			for (int index = 0; index < values.length; index++) {
				if ((indices & 1 << index) == 0)
					continue;

				int value = values[index];
				if (!hasApplied || !Objects.equals(value, appliedValues[index])) {
					applyValue(index, value);
					appliedValues[index] = value;
				}
			}
		}

		protected abstract void applyValue(int attachment, int value);

		@Override
		public void reset() {
			super.reset();
			indices = 0;
		}
	}

	public abstract static class IndexedIntArray extends GLState {
		private static final int MAX_ATTACHMENTS = 8;

		private final int perIndex;
		private final int[] values;
		private final int[] appliedValues;
		private int indices;

		protected IndexedIntArray(int perIndex) {
			this.perIndex = perIndex;
			values = new int[MAX_ATTACHMENTS * perIndex];
			appliedValues = new int[MAX_ATTACHMENTS * perIndex];
		}

		protected final void setValue(int index, int... v) {
			if (index < 0 || index >= MAX_ATTACHMENTS)
				throw new IllegalArgumentException("Invalid index: " + index);
			if (v.length != perIndex)
				throw new IllegalArgumentException("Expected " + perIndex + " values, got " + v.length);

			hasValue = true;
			System.arraycopy(v, 0, values, index * perIndex, perIndex);
			indices |= 1 << index;
		}

		@Override
		protected final void internalApply() {
			for (int index = 0; index < MAX_ATTACHMENTS; index++) {
				if ((indices & 1 << index) == 0)
					continue;

				int offset = index * perIndex;
				boolean changed = !hasApplied;
				if (!changed) {
					for (int i = 0; i < perIndex; i++) {
						if (values[offset + i] != appliedValues[offset + i]) {
							changed = true;
							break;
						}
					}
				}
				if (changed) {
					applyValue(index, values, offset);
					System.arraycopy(values, offset, appliedValues, offset, perIndex);
				}
			}
		}

		protected abstract void applyValue(int attachment, int[] values, int offset);

		@Override
		public void reset() {
			super.reset();
			Arrays.fill(values, 0);
			Arrays.fill(appliedValues, 0);
			indices = 0;
		}
	}
}
