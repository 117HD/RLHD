package rs117.hd.utils.jobs;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.HDUtils.getThreadStackTrace;
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
	final BlockingDeque<JobHandle> localWorkQueue = new LinkedBlockingDeque<>();
	final AtomicBoolean inflight = new AtomicBoolean();

	void run() {
		name = thread.getName();
		pausedName = name + " [Paused]";
		ThreadLocalRandom random = ThreadLocalRandom.current();
		while (jobSystem.active) {
			// Check local work queue
			handle = localWorkQueue.poll();

			while (handle == null) {
				if (stealTargetIdx >= 0) {
					final Worker victim = jobSystem.workers[stealTargetIdx];
					int stealCount = victim.localWorkQueue.size() / 2;

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
					// Still no work, wait longer on the main work Queue
					handle = jobSystem.workQueue.poll();
				}

				if(handle == null) {
					// Find the best target to steal work from
					int nextVictimIdx = (workerIdx + 1) % jobSystem.workers.length;
					int nextVictimWorkCount = jobSystem.workers[nextVictimIdx].localWorkQueue.size();
					for(int i = 0; i < jobSystem.workers.length; i++) {
						if(i == workerIdx) continue;
						int workCount = jobSystem.workers[i].localWorkQueue.size();
						if(workCount > nextVictimWorkCount) {
							nextVictimIdx = i;
							nextVictimWorkCount = workCount;
						}
					}
					stealTargetIdx = nextVictimIdx;

					if(nextVictimWorkCount == 0) {
						// Wait for a signal that there is work to be had
						try {
							jobSystem.workerSemaphore.acquire();
						} catch (InterruptedException ignored) {
							// Interrupts are used to signal that the worker should shutdown, we'll pick this up and shutdown
						}
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
		try {
			workerHandleCancel();

			if (handle.item != null && handle.setRunning(this)) {
				inflight.lazySet(true);
				handle.item.onRun();
				handle.item.ranToCompletion.set(true);
			}
		} catch (InterruptedException e) {
			log.debug("Interrupt Received whilst processing: {}", handle.hashCode());
		} catch (Throwable ex) {
			log.warn("Encountered an error whilst processing: {}", handle.hashCode(), ex);
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
