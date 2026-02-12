package rs117.hd.utils.jobs;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
	final ConcurrentLinkedDeque<JobHandle> localWorkQueue = new ConcurrentLinkedDeque<>();
	final ArrayDeque<JobHandle> localStalledWork = new ArrayDeque<>();
	final AtomicBoolean inflight = new AtomicBoolean();

	boolean findNextStealTarget() {
		// Find the best target to steal work from
		int nextVictimIdx = -1;
		int nextVictimWorkCount = -1;
		for (int i = 0; i < jobSystem.workers.length; i++) {
			if (i == workerIdx || !jobSystem.workers[i].inflight.get())
				continue; // Don't query ourselves or a worker that is idle
			int workCount = jobSystem.workers[i].localWorkQueue.size();
			if (workCount > nextVictimWorkCount) {
				nextVictimIdx = i;
				nextVictimWorkCount = workCount;
			}
		}
		stealTargetIdx = nextVictimIdx;
		return nextVictimWorkCount > 0;
	}

	void run() {
		name = thread.getName();
		pausedName = name + " [Paused]";
		while (jobSystem.active) {
			// Check local work queue
			handle = (localStalledWork.isEmpty() ? localWorkQueue : localStalledWork).poll();

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
					handle = jobSystem.workQueue.poll();
				}

				if (handle == null && !findNextStealTarget()) {
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

					if (handle == null) {
						// No work in the main queue, this must mean it was pushed to a local queue and as such should find it
						findNextStealTarget();
					}
				}

				if (!jobSystem.active) {
					log.debug("Shutdown");
					return;
				}
			}

			try {
				processHandle();
			} catch (InterruptedException ignored) {
				thread.isInterrupted(); // Consume the interrupt to prevent it from cancelling the next job
			}
		}
		log.debug("Shutdown - {}", jobSystem.active);
	}

	void processHandle() throws InterruptedException {
		boolean requeued = false;
		try {
			workerHandleCancel();

			if (handle.item != null) {
				if (handle.item.canStart()) {
					if (handle.setRunning(this)) {
						inflight.set(true);
						handle.item.onRun();
						handle.item.ranToCompletion.set(true);
					}
				} else {
					// Requeue into stalled work queue, since adding to CosncurrentLinkedDeque continuously is costly
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
