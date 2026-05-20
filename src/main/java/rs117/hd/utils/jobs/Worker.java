package rs117.hd.utils.jobs;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.platform.PlatformBindings;

import static rs117.hd.utils.HDUtils.getThreadStackTrace;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.jobs.JobSystem.VALIDATE;

@Slf4j
@RequiredArgsConstructor
public final class Worker {
	String name, pausedName;
	Thread thread;
	JobHandle handle;
	int stealTargetIdx = -1;

	final JobSystem jobSystem;
	final int workerIdx;

	final ArrayDeque<JobHandle> localWorkQueue = new ArrayDeque<>();

	final AtomicInteger queueDepth = new AtomicInteger();
	final AtomicInteger unparkedStamp = new AtomicInteger();
	final AtomicInteger parkedStamp = new AtomicInteger();

	boolean findNextStealTarget() {
		int nextVictimIdx = -1;
		int nextVictimWorkCount = -1;

		for (int i = 1; i < jobSystem.workers.length; i++) {
			final Worker worker = jobSystem.workers[(workerIdx + i) % jobSystem.workers.length];
			if (i == workerIdx)
				continue;

			int workCount = worker.queueDepth.get();
			if (workCount <= 1)
				continue;

			if (workCount > nextVictimWorkCount) {
				nextVictimIdx = i;
				nextVictimWorkCount = workCount;
			}
		}

		stealTargetIdx = nextVictimIdx;
		return nextVictimWorkCount > 0;
	}

	void push(JobHandle handle) {
		synchronized (localWorkQueue) {
			if (handle.highPriority) {
				localWorkQueue.addFirst(handle);
			} else {
				localWorkQueue.addLast(handle);
			}
		}

		queueDepth.incrementAndGet();
		if(unparkedStamp.compareAndSet(unparkedStamp.get(), parkedStamp.get()))
			LockSupport.unpark(thread);
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	void run() {
		init();

		while (jobSystem.active) {
			if(!acquireHandle())
				break;

			try {
				processHandle();
			} catch (InterruptedException ignored) {
				thread.isInterrupted();
			}
		}

		log.trace("Shutdown");
	}

	private void init() {
		name = thread.getName();
		pausedName = name + " [Paused]";

		int affinityCore = workerIdx + 1;
		if(PlatformBindings.setAffinity(1L << affinityCore)) {
			log.trace("Set worker {} affinity to {}", workerIdx, affinityCore);
			Thread.yield(); // Yield to ensure affinity is set
		}

		int cpu = PlatformBindings.getCpu();
		if (cpu != -1 && affinityCore != cpu) {
			log.warn(
				"Expected worker {} to be on core {}, but it is on core {}",
				workerIdx,
				affinityCore,
				cpu
			);
		}
	}

	JobHandle pollFirst() {
		if(localWorkQueue.isEmpty())
			return null;

		synchronized (localWorkQueue) {
			return localWorkQueue.pollFirst();
		}
	}

	JobHandle pollLast() {
		if(localWorkQueue.isEmpty())
			return null;

		synchronized (localWorkQueue) {
			return localWorkQueue.pollLast();
		}
	}

	private boolean acquireHandle() {
		handle = pollFirst();

		while (handle == null) {
			if (stealTargetIdx >= 0) {
				final Worker victim = jobSystem.workers[stealTargetIdx];

				int stealCount = max(1, victim.queueDepth.get() / jobSystem.workers.length);
				JobHandle stolenHandle;

				while (stealCount-- > 0 &&  (stolenHandle = victim.pollLast()) != null) {
					victim.queueDepth.decrementAndGet();
					if (handle == null) {
						handle = stolenHandle;
					} else {
						push(stolenHandle);
					}
				}
			}

			if (handle == null && !findNextStealTarget()) {
				parkedStamp.incrementAndGet();
				LockSupport.park(this);

				if (!jobSystem.active) {
					log.trace("Shutdown");
					return false;
				}

				handle = pollFirst();
			}

			if (!jobSystem.active) {
				log.trace("Shutdown");
				return false;
			}
		}

		queueDepth.decrementAndGet();
		return true;
	}

	private void processHandle() throws InterruptedException {
		try {
			workerHandleCancel();

			if (handle.item != null) {
				if (handle.setRunning(this)) {
					handle.item.onRun();
					handle.item.ranToCompletion.set(true);
				}
			}
		} catch (InterruptedException e) {
			log.debug("Interrupt Received whilst processing: {}", handle.hashCode());
		} catch (Throwable ex) {
			if (handle.item.wasCancelled()) {
				log.debug("Encountered an error whilst processing: {}", handle.hashCode(), ex);
			} else {
				log.warn("Encountered an error whilst processing: {}", handle.hashCode(), ex);
			}

			handle.item.encounteredError.set(true);
			handle.cancel(false);
		} finally {
			if (handle.item != null && handle.item.wasCancelled.get())
				handle.item.onCancel();

			handle.setCompleted();
			handle.worker = null;
			handle = null;
		}
	}

	void workerHandleCancel() throws InterruptedException {
		if (handle.isCancelled()) {
			if (VALIDATE) log.debug("Handle {} has been cancelled, interrupting to exit execution", handle);
			throw new InterruptedException();
		}
	}

	void printState() {
		log.debug("Worker {} is {}", thread.getName(), handle == null ? "idle" : "running:\n" + getThreadStackTrace(thread));
	}
}
