package rs117.hd.utils.collections;

import com.google.inject.Injector;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.LockSupport;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class ConcurrentPool<T> {

	// TODO: Ideally replace with a non allocating pool?
	private final ConcurrentLinkedDeque<T> pool = new ConcurrentLinkedDeque<>();
	private final ConcurrentLinkedDeque<Thread> parkedThreads;

	private final Injector injector;
	private final Class<T> clazz;
	private final int fixedSize;
	private int created;

	public ConcurrentPool(Injector injector, Class<T> clazz) {
		this(injector, clazz, 0);
	}

	public ConcurrentPool(Injector injector, Class<T> clazz, int fixedSize) {
		this.injector = injector;
		this.clazz = clazz;
		this.fixedSize = fixedSize;
		parkedThreads = fixedSize > 0 ? new ConcurrentLinkedDeque<>() : null;
	}

	public T acquire() {
		T obj = pool.poll();
		if (obj == null && (fixedSize == 0 || created < fixedSize)) {
			obj = injector.getInstance(clazz);
			created++;
		}
		return obj;
	}

	public T acquireBlocking() {
		T obj = acquire();
		if (obj == null && parkedThreads != null) {
			final Thread currentThread = Thread.currentThread();
			while((obj = pool.poll()) == null) {
				if(!parkedThreads.contains(currentThread))
					parkedThreads.add(currentThread);
				LockSupport.parkNanos(1000);
			}
			parkedThreads.remove(currentThread);
		}
		return obj;
	}

	public void recycle(T obj) {
		pool.offer(obj);

		if(parkedThreads != null){
			Thread parkedThread = parkedThreads.poll();
			if(parkedThread != null)
				LockSupport.unpark(parkedThread);
		}
	}
}
