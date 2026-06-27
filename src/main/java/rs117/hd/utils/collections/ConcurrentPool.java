package rs117.hd.utils.collections;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;
import rs117.hd.utils.Props;

public final class ConcurrentPool<T> {
	public static List<ConcurrentPool<?>> ALL_POOLS = new ArrayList<>();

	private final ConcurrentLinkedQueue<Thread> parkedThreads;
	private final ArrayDeque<T> pool = new ArrayDeque<>();

	private final Supplier<T> supplier;
	private final int fixedSize;
	private int created;

	public ConcurrentPool(@Nonnull Supplier<T> supplier) {
		this(supplier, 0);
	}

	public ConcurrentPool(@Nonnull Supplier<T> supplier, int fixedSize) {
		this.supplier = supplier;
		this.fixedSize = fixedSize;
		parkedThreads = fixedSize > 0 ? new ConcurrentLinkedQueue<>() : null;
		ALL_POOLS.add(this);
	}

	private T poll() {
		synchronized (pool) {
			return pool.poll();
		}
	}

	private void offer(T obj) {
		synchronized (pool) {
			assert !Props.DEVELOPMENT || pool.isEmpty() || !pool.contains(obj) : "Object already in pool: " + obj;
			pool.offer(obj);
		}
	}

	public T acquire() {
		T obj = poll();
		if (obj == null && (fixedSize == 0 || created < fixedSize)) {
			obj = supplier.get();
			created++;
		}
		return obj;
	}

	public T acquireBlocking(int timeoutNanos) {
		T obj = acquire();
		if (obj == null && parkedThreads != null) {
			final Thread currentThread = Thread.currentThread();
			final long deadline = System.nanoTime() + timeoutNanos;
			while ((obj = poll()) == null) {
				if (!parkedThreads.contains(currentThread))
					parkedThreads.add(currentThread);
				LockSupport.parkNanos(1000);
				if (System.nanoTime() > deadline)
					return null;
			}
			parkedThreads.remove(currentThread);
		}
		return obj;
	}

	public void recycle(T obj) {
		if (obj == null)
			return;

		if (DestructibleHandler.isShuttingDown() && obj instanceof Destructible) {
			((Destructible) obj).destroy();
			return;
		}

		offer(obj);

		if (parkedThreads != null) {
			Thread parkedThread = parkedThreads.poll();
			if (parkedThread != null)
				LockSupport.unpark(parkedThread);
		}
	}

	public void destroy() {
		T obj;
		while ((obj = poll()) != null) {
			if (obj instanceof Destructible)
				((Destructible) obj).destroy();
		}
		ALL_POOLS.remove(this);
		created = 0;
	}

	public static void destroyAll() {
		ConcurrentPool<?> pool;
		while (!ALL_POOLS.isEmpty() && (pool = ALL_POOLS.remove(0)) != null)
			pool.destroy();
		ALL_POOLS.clear();
	}
}
