package rs117.hd.utils.jobs;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.Props;
import rs117.hd.utils.platform.PlatformBindings;

import static rs117.hd.utils.HDUtils.getThreadStackTrace;
import static rs117.hd.utils.MathUtils.*;
import static rs117.hd.utils.jobs.JobSystem.VALIDATE;

@Slf4j
@RequiredArgsConstructor
public final class Worker {
	static final long MIN_SLEEP_NANOS = 1_000;
	static final long MAX_SLEEP_NANOS = 1_000_000; // 1ms

	static final double SLEEP_ALPHA = 0.1;

	String name, pausedName;
	Thread thread;
	JobHandle handle;
	int stealTargetIdx = -1;

	final JobSystem jobSystem;
	final int workerIdx;
	final ConcurrentLinkedDeque<JobHandle> localWorkQueue = new ConcurrentLinkedDeque<>();
	final ArrayDeque<JobHandle> localStalledWork = new ArrayDeque<>();
	final AtomicBoolean processing = new AtomicBoolean();

	boolean findNextStealTarget() {
		// Find the best target to steal work from
		int nextVictimIdx = -1;
		int nextVictimWorkCount = -1;
		for (int i = 0; i < jobSystem.workers.length; i++) {
			final Worker worker = jobSystem.workers[i];
			if (i == workerIdx || !worker.processing.get() || worker.localWorkQueue.isEmpty())
				continue; // Don't query ourselves or a worker that is idle
			int workCount = worker.localWorkQueue.size();
			if (workCount > nextVictimWorkCount) {
				nextVictimIdx = i;
				nextVictimWorkCount = workCount;
			}
		}
		stealTargetIdx = nextVictimIdx;
		return nextVictimWorkCount > 0;
	}

	@SuppressWarnings("ResultOfMethodCallIgnored")
	void run() {
		name = thread.getName();
		pausedName = name + " [Paused]";

		int affinityCore = workerIdx + 1;
		if(PlatformBindings.setAffinity(1L << affinityCore))
			log.trace("Set worker {} affinity to {}", workerIdx, affinityCore);

		long spinWaitNanos = TimeUnit.MICROSECONDS.convert(1, TimeUnit.NANOSECONDS);
		long nextCPUCoreCheck = Props.DEVELOPMENT ? 0 : -1;

		while (jobSystem.active) {
			// Check local work queue
			handle = (localStalledWork.isEmpty() ? localWorkQueue : localStalledWork).poll();

			// Check if the worker is on the correct core, helps determine if CPU Pinning is working correctly on platforms
			if(nextCPUCoreCheck >= 0 && System.nanoTime() > nextCPUCoreCheck) {
				int cpu = PlatformBindings.getCpu();
				if (cpu != -1) {
					if (cpu != affinityCore) {
						log.warn("Expected worker {} to be on core {}, but it is on core {}", workerIdx, affinityCore, cpu);
						nextCPUCoreCheck = -1L;
					} else {
						nextCPUCoreCheck = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
					}
				} else {
					nextCPUCoreCheck = -1L;
				}
			}

			long idleStart = handle == null ? System.nanoTime() : 0;
			long idleTime = 0;
			while (handle == null) {
				if (stealTargetIdx >= 0) {
					final Worker victim = jobSystem.workers[stealTargetIdx];
					int stealCount = max(1, victim.localWorkQueue.size() / jobSystem.workers.length);

					JobHandle stolenHandle;
					while (stealCount-- > 0 && (stolenHandle = victim.localWorkQueue.poll()) != null) {
						if (handle == null) {
							handle = stolenHandle;
						} else {
							if (handle.highPriority)
								localWorkQueue.addFirst(stolenHandle);
							else
								localWorkQueue.addLast(stolenHandle);
						}
					}
				}

				if (handle == null) {
					// Check if any work is in the main queue before attempting to steal again
					handle = localStalledWork.isEmpty() ? jobSystem.workQueue.poll() : localStalledWork.poll();
				}

				idleTime = System.nanoTime() - idleStart;
				if (handle == null && !findNextStealTarget() && idleTime > spinWaitNanos) {
					// Wait for a signal that there is work to be had
					try {
						jobSystem.workerSemaphore.acquire();
					} catch (InterruptedException ignored) {
						// Interrupts are used to signal that the worker should shutdown, we'll pick this up and shutdown
						thread.isInterrupted(); // Consume the interrupt to prevent it from cancelling the next job
					}

					if (handle == null) {
						// We've been signaled that there is work to be had, try the main queue again
						handle = jobSystem.workQueue.poll();
					}
				}

				if(handle == null)
					Thread.onSpinWait();

				if (!jobSystem.active) {
					log.trace("Shutdown");
					return;
				}
			}

			if (idleStart != 0) {
				spinWaitNanos = clamp(
					(long) (
						spinWaitNanos * (1.0 - SLEEP_ALPHA) +
						idleTime * SLEEP_ALPHA
					),
					MIN_SLEEP_NANOS,
					MAX_SLEEP_NANOS
				);
			}

			try {
				processing.set(true);
				processHandle();
			} catch (InterruptedException ignored) {
				thread.isInterrupted(); // Consume the interrupt to prevent it from cancelling the next job
			} finally {
				processing.set(false);
			}
		}
		log.trace("Shutdown");
	}

	void processHandle() throws InterruptedException {
		boolean requeued = false;
		try {
			workerHandleCancel();

			if (handle.item != null) {
				if (handle.item.canStart()) {
					if (handle.setRunning(this)) {
						handle.item.onRun();
						handle.item.ranToCompletion.set(true);
					}
				} else {
					// Requeue into stalled work queue, since adding to ConcurrentLinkedDeque continuously is costly
					localStalledWork.addLast(handle);
					requeued = true;
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
			if (!requeued) {
				if (handle.item != null && handle.item.wasCancelled.get())
					handle.item.onCancel();
				handle.setCompleted();
				handle.worker = null;
			}
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
