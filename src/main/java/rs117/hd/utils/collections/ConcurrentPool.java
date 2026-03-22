package rs117.hd.utils.collections;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import rs117.hd.utils.Destructible;
import rs117.hd.utils.DestructibleHandler;

public final class ConcurrentPool<T> {
	private final ConcurrentLinkedQueue<T> pool = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Thread> parkedThreads;

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
	}

	public T acquire() {
		T obj = pool.poll();
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
			while ((obj = pool.poll()) == null) {
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

		assert !pool.contains(obj) : "Object already in pool: " + obj;
		pool.offer(obj);

		if (parkedThreads != null) {
			Thread parkedThread = parkedThreads.poll();
			if (parkedThread != null)
				LockSupport.unpark(parkedThread);
		}
	}

	public void destroy() {
		T obj;
		while ((obj = pool.poll()) != null) {
			if (obj instanceof Destructible)
				((Destructible) obj).destroy();
		}
		created = 0;
	}
}
