package rs117.hd.utils.collections;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.system.MemoryUtil;

import static rs117.hd.utils.MathUtils.*;

@Slf4j
public enum NativeBufferPool {
	BYTE(PooledArrayType.BYTE, MemoryUtil::memAlloc),
	SHORT(PooledArrayType.SHORT, MemoryUtil::memAllocShort),
	INT(PooledArrayType.INT, MemoryUtil::memAllocInt),
	FLOAT(PooledArrayType.FLOAT, MemoryUtil::memAllocFloat);

	private static final int MAX_BUCKET = 30;
	private static final AtomicLong CURRENT_BYTES = new AtomicLong();

	private static NativeBufferPool poolFor(PooledArrayType type) {
		switch (type) {
			case BYTE: return BYTE;
			case SHORT: return SHORT;
			case INT: return INT;
			case FLOAT: return FLOAT;
			default: throw new IllegalArgumentException("Unsupported native buffer type: " + type);
		}
	}

	@Nullable
	@SuppressWarnings("unchecked")
	public static Buffer borrow(PooledArrayType type, int requestedSize) {
		final NativeBufferPool pool = poolFor(type);
		final int capacity = ceilPow2(max(requestedSize, 1));
		final int bucket = PooledArrayType.bucket(capacity);

		if(!pool.buckets[bucket].isEmpty()) {
			synchronized (pool.buckets[bucket]) {
				final Buffer buffer = pool.buckets[bucket].poll();
				if (buffer != null) {
					buffer.clear();
					return buffer;
				}
			}
		}

		final long bytes = (long) capacity * pool.arrayType.stride;
		try {
			CURRENT_BYTES.addAndGet(bytes);
			return pool.allocator.allocate(capacity);
		} catch (OutOfMemoryError e) {
			CURRENT_BYTES.addAndGet(-bytes);
			log.warn("Unable to allocate {} bytes of native memory for model caching", bytes);
			return null;
		}
	}

	public static void release(PooledArrayType type, @Nullable Buffer buffer) {
		if (buffer == null)
			return;

		final NativeBufferPool pool = poolFor(type);
		final int capacity = buffer.capacity();
		final int bucket = PooledArrayType.bucket(capacity);
		buffer.clear();
		synchronized (pool.buckets[bucket]) {
			pool.buckets[bucket].offer(buffer);
		}
	}

	public static void copyToNative(PooledArrayType type, Buffer destination, Object source, int offset, int length) {
		switch (type) {
			case BYTE: {
				ByteBuffer dst = (ByteBuffer) destination;
				dst.clear();
				dst.put((byte[]) source, offset, length);
				break;
			}
			case SHORT: {
				ShortBuffer dst = (ShortBuffer) destination;
				dst.clear();
				dst.put((short[]) source, offset, length);
				break;
			}
			case INT: {
				IntBuffer dst = (IntBuffer) destination;
				dst.clear();
				dst.put((int[]) source, offset, length);
				break;
			}
			case FLOAT: {
				FloatBuffer dst = (FloatBuffer) destination;
				dst.clear();
				dst.put((float[]) source, offset, length);
				break;
			}
			default:
				throw new IllegalArgumentException("Unsupported native buffer type: " + type);
		}
	}

	public static void copyFromNative(PooledArrayType type, Buffer source, Object destination, int length) {
		switch (type) {
			case BYTE: {
				ByteBuffer src = (ByteBuffer) source;
				src.clear();
				src.get((byte[]) destination, 0, length);
				break;
			}
			case SHORT: {
				ShortBuffer src = (ShortBuffer) source;
				src.clear();
				src.get((short[]) destination, 0, length);
				break;
			}
			case INT: {
				IntBuffer src = (IntBuffer) source;
				src.clear();
				src.get((int[]) destination, 0, length);
				break;
			}
			case FLOAT: {
				FloatBuffer src = (FloatBuffer) source;
				src.clear();
				src.get((float[]) destination, 0, length);
				break;
			}
			default:
				throw new IllegalArgumentException("Unsupported native buffer type: " + type);
		}
	}

	public static long getCurrentBytes() {
		return CURRENT_BYTES.get();
	}

	public static void destroy() {
		for (NativeBufferPool pool : NativeBufferPool.values()) {
			for (int i = 0; i < MAX_BUCKET; i++) {
				synchronized (pool.buckets[i]) {
					Buffer buffer;
					while ((buffer = pool.buckets[i].poll()) != null) {
						CURRENT_BYTES.addAndGet(-(long) buffer.capacity() * pool.arrayType.stride);
						MemoryUtil.memFree(buffer);
					}
				}
			}
		}
		CURRENT_BYTES.set(0);
	}

	@FunctionalInterface
	private interface Allocator {
		Buffer allocate(int capacity);
	}

	private final PooledArrayType arrayType;
	private final Allocator allocator;
	@SuppressWarnings("unchecked")
	private final ArrayDeque<Buffer>[] buckets = new ArrayDeque[MAX_BUCKET + 1];

	NativeBufferPool(PooledArrayType arrayType, Allocator allocator) {
		this.arrayType = arrayType;
		this.allocator = allocator;
		for (int i = 0; i < buckets.length; i++)
			buckets[i] = new ArrayDeque<>();
	}
}
