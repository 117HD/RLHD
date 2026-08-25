package rs117.hd.utils.collections;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import javax.annotation.concurrent.ThreadSafe;

// Based on https://grokipedia.com/page/treiber_stack with optimizations and changes to work with an Intrusive approach rather than Nodes
//    * Original uses `compareAndSet` which has been changed to `compareAndExchange` to avoid having to call `get` since we can just use the
//      witness value from the CAS operation.
//    * `tryClaim` & `tryMarkInStack` are added to avoid ABA problems, when under high thread contention.

@ThreadSafe
public final class IntrusiveTreiberStack<N extends IntrusiveTreiberStack.Node<N>> extends AtomicReference<N> {
	private final AtomicInteger count = new AtomicInteger();

	public boolean push(N node) {
		if (!node.tryMarkInStack())
			return false; // already in the stack somewhere - reject, don't corrupt the list

		N oldHead = get();
		for (int failures = 0;;) {
			node.setNext(oldHead);
			final N witness = compareAndExchange(oldHead, node);
			if (witness == oldHead)
				break;
			oldHead = witness;
			backoff(++failures);
		}
		count.incrementAndGet();
		return true;
	}

	public N pop() {
		N oldHead = get();
		for (int failures = 0;;) {
			if (oldHead == null)
				return null;

			final N newHead = oldHead.getNext();
			final N witness = compareAndExchange(oldHead, newHead);
			if (witness == oldHead) {
				oldHead.setNext(null); // don't pin the rest of the (now detached) chain in memory

				if (oldHead.tryClaim()) {
					count.decrementAndGet();
					return oldHead;
				}

				oldHead = get();
				continue;
			}

			oldHead = witness;
			backoff(++failures);
		}
	}

	public void drain(Consumer<N> action) {
		N node = getAndSet(null);
		int drained = 0;
		while (node != null) {
			final N next = node.getNext();
			node.setNext(null);
			action.accept(node);
			node = next;
			drained++;
		}
		count.addAndGet(-drained);
	}

	public int size() {
		return count.get();
	}

	public boolean isEmpty() {
		return get() == null;
	}

	private static void backoff(int failures) {
		if (failures < 10) {
			Thread.onSpinWait();
		} else if (failures < 20) {
			Thread.yield();
		} else {
			LockSupport.parkNanos(Math.min(1_000, 1L << Math.min(failures - 20, 10)));
		}
	}

	public interface Node<N extends Node<N>> {
		N getNext();
		void setNext(N next);
		boolean tryClaim();
		boolean tryMarkInStack();
	}
}