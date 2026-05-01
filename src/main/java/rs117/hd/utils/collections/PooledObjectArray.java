package rs117.hd.utils.collections;

@SuppressWarnings("unchecked")
public final class PooledObjectArray<T> {
	public Object[] array;

	public T get(int idx) { return (T) array[idx]; }

	public void set(int idx, T value) { array[idx] = value; }

	public void ensureCapacity(int size) {
		array = PooledArrayType.OBJECT.ensureCapacity(array, size);
	}

	public void release() {
		if(array != null)
			PooledArrayType.OBJECT.release(array);
		array = null;
	}
}
