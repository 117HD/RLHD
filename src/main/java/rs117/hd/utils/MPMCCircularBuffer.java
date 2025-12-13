package rs117.hd.utils;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.Queue;

import static rs117.hd.utils.HDUtils.ceilPow2;

/**
 * A lock-free multi-producer, multi-consumer bounded MPMC queue
 * based on Dmitry Vyukov's algorithm.
 * <a href="https://www.1024cores.net/home/lock-free-algorithms/queues">1024cores lock free queues</a>
 * <a href="https://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue">1024cores bounded mpmc queue</a>
 *
 * Notes:
 * - size() and isEmpty() are NOT linearizable (approximate).
 * - remove(), and blocking operations are not supported.
 */
public final class MPMCCircularBuffer<T> extends AbstractQueue<T> implements Queue<T> {

	/**
	 * VarHandle is used instead of Atomic* because Vyukov’s MPMC algorithm requires
	 * precise memory-ordering (acquire/release) without the full volatile fences that
	 * AtomicInteger/AtomicLong always impose. VarHandle also allows direct access to
	 * embedded fields (including per-slot sequence numbers) and supports padding to
	 * avoid false sharing—both essential for achieving high throughput in this queue.
	 */
	private static final VarHandle HEAD;
	private static final VarHandle TAIL;
	private static final VarHandle CELL_SEQ;
	private static final VarHandle CELL_VAL;

	static {
		try {
			MethodHandles.Lookup l = MethodHandles.lookup();
			HEAD = l.findVarHandle(MPMCCircularBuffer.class, "head", long.class);
			TAIL = l.findVarHandle(MPMCCircularBuffer.class, "tail", long.class);
			CELL_SEQ = l.findVarHandle(Cell.class, "seq", long.class);
			CELL_VAL = l.findVarHandle(Cell.class, "value", Object.class);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// ---------- Fields with padding to prevent false sharing ----------
	// Note: Contented would be better here see: https://jenkov.com/tutorials/java-concurrency/false-sharing.html for a detailed explanation
	@SuppressWarnings("FieldMayBeFinal")
	private volatile long head = 0;
	@SuppressWarnings("unused")
	private long head_p1, head_p2, head_p3, head_p4, head_p5, head_p6, head_p7;

	@SuppressWarnings("FieldMayBeFinal")
	private volatile long tail = 0;
	@SuppressWarnings("unused")
	private long tail_p1, tail_p2, tail_p3, tail_p4, tail_p5, tail_p6, tail_p7;

	private final int capacity;
	private final long mask;
	private final Cell<T>[] buffer;

	@SuppressWarnings("unchecked")
	public MPMCCircularBuffer(int initialCapacity) {
		capacity = (int) ceilPow2(initialCapacity);
		mask = capacity - 1;
		buffer = new Cell[capacity];

		for (int i = 0; i < capacity; i++)
			buffer[i] = new Cell<>(i);
	}

	/** Backoff strategy: spin, then yield, then short sleep under contention. */
	private static int backoff(int spins) {
		if (spins < 32) {
			Thread.onSpinWait();
		} else if (spins < 64) {
			Thread.yield();
		} else {
			try { Thread.sleep(0, 1); } catch (InterruptedException ignored) {}
		}
		return spins + 1;
	}

	/**
	 * Offer inserts an element if space is available.
	 * It reserves a slot by CAS-ing tail, then publishes the value.
	 * Returns false if the buffer is full.
	 */
	@Override
	public boolean offer(T e) {
		if (e == null) throw new NullPointerException();

		int spins = 1;

		while (true) {
			long t = (long) TAIL.getAcquire(this);
			Cell<T> cell = buffer[(int) (t & mask)];

			long seq = (long) CELL_SEQ.getAcquire(cell);
			long diff = seq - t;

			if (diff == 0) { // slot is free
				if (TAIL.weakCompareAndSetRelease(this, t, t + 1)) {
					CELL_VAL.setRelease(cell, e);
					CELL_SEQ.setRelease(cell, t + 1);
					return true;
				}
			} else if (diff < 0) {
				return false; // full
			} else {
				spins = backoff(spins);
			}
		}
	}

	/**
	 * Poll removes and returns the next element if available.
	 * It CAS-es head to claim the slot, then clears and recycles it.
	 * Returns null if the buffer is empty.
	 */
	@Override
	public T poll() {
		int spins = 1;

		while (true) {
			long h = (long) HEAD.getAcquire(this);
			Cell<T> cell = buffer[(int) (h & mask)];

			long seq = (long) CELL_SEQ.getAcquire(cell);
			long diff = seq - (h + 1);

			if (diff == 0) { // element is available
				if (HEAD.weakCompareAndSetRelease(this, h, h + 1)) {
					@SuppressWarnings("unchecked")
					T value = (T) CELL_VAL.getAcquire(cell);

					CELL_VAL.setOpaque(cell, null);
					CELL_SEQ.setRelease(cell, h + capacity);
					return value;
				}
			} else if (diff < 0) {
				return null; // empty
			} else {
				spins = backoff(spins);
			}
		}
	}

	/**
	 * Peek returns the next element without removing it.
	 * It scans forward from head until it finds a non-null value.
	 * This is weakly consistent and may miss or see stale elements.
	 */
	@Override
	public T peek() {
		long start = (long) HEAD.getAcquire(this);
		long end   = (long) TAIL.getAcquire(this);

		for (long p = start; p < end; p++) {
			Cell<T> cell = buffer[(int) (p & mask)];

			@SuppressWarnings("unchecked")
			T v = (T) CELL_VAL.getAcquire(cell);
			if (v != null)
				return v;
		}
		return null;
	}

	/**
	 * Iterator scans from current head to tail and yields any non-null values.
	 * It does not reflect real-time queue changes (weakly consistent).
	 * next() returns null instead of throwing when exhausted.
	 */
	@Override
	public Iterator<T> iterator() {
		final long start = (long) HEAD.getAcquire(this);
		final long end   = (long) TAIL.getAcquire(this);

		return new Iterator<>() {
			long idx = start;
			T next = null;

			@Override
			public boolean hasNext() {
				if (next != null) return true;

				while (idx < end) {
					long p = idx++;
					Cell<T> cell = buffer[(int) (p & mask)];

					@SuppressWarnings("unchecked")
					T v = (T) CELL_VAL.getAcquire(cell);
					if (v != null) {
						next = v;
						return true;
					}
				}
				return false;
			}

			@Override
			public T next() {
				if (!hasNext()) return null;
				T v = next;
				next = null;
				return v;
			}
		};
	}

	/**
	 * Returns an approximate size (non-linearizable).
	 * The value may be negative or out of date under heavy contention.
	 */
	@Override
	public int size() {
		long h = (long) HEAD.getVolatile(this);
		long t = (long) TAIL.getVolatile(this);
		return (int) (t - h);
	}

	/**
	 * Returns whether the queue is (approximately) empty.
	 * May briefly return true even if elements are in-flight.
	 */
	@Override
	public boolean isEmpty() {
		return ((long) HEAD.getVolatile(this) == (long) TAIL.getVolatile(this));
	}

	/**
	 * Arbitrary removal is not supported in Vyukov's MPMC algorithm.
	 * This operation would break queue invariants and correctness.
	 */
	@Override
	public boolean remove(Object o) {
		throw new UnsupportedOperationException("remove(o) is not supported in MPMC queues");
	}

	/**
	 * Per-slot data: holds the sequence tag and stored value.
	 * Padded to prevent false sharing.
	 */
	private static final class Cell<E> {
		volatile long seq;
		@SuppressWarnings("unused")
		private long p1, p2, p3, p4, p5, p6, p7;

		volatile E value;
		@SuppressWarnings("unused")
		private long q1, q2, q3, q4, q5, q6, q7;

		Cell(long s) { seq = s; }
	}
}
