package rs117.hd.utils.jobs;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.HDUtils.printStacktrace;
import static rs117.hd.utils.jobs.JobSystem.VALIDATE;

@Slf4j
@RequiredArgsConstructor
public final class JobWorker {
	String name, pausedName;
	Thread thread;
	JobHandle handle;
	int stealTargetIdx = -1;

	final int workerIdx;
	final BlockingDeque<JobHandle> localWorkQueue = new LinkedBlockingDeque<>();
	final AtomicBoolean inflight = new AtomicBoolean();

	void run() {
		name = thread.getName();
		pausedName = name + " [Paused]";
		ThreadLocalRandom random = ThreadLocalRandom.current();
		while (JobSystem.INSTANCE.active) {
			// Check local work queue
			handle = localWorkQueue.poll();

			while(handle == null) {
				if(stealTargetIdx >= 0) {
					final JobWorker victim = JobSystem.INSTANCE.workers[stealTargetIdx];
					int stealCount = victim.localWorkQueue.size() / 2;

					JobHandle stolenHandle;
					while (stealCount-- > 0 && (stolenHandle = victim.localWorkQueue.poll()) != null) {
						if(handle == null) {
							handle = stolenHandle;
						} else {
							if(handle.highPriority)
								localWorkQueue.addFirst(stolenHandle);
							else
								localWorkQueue.addLast(stolenHandle);
						}
					}
				}

				if (handle == null) {
					// Reset steal worker idx since its queue is empty
					stealTargetIdx = random.nextInt(0, JobSystem.INSTANCE.workers.length);

					if(stealTargetIdx == workerIdx) // Don't steal from yourself
						stealTargetIdx = (stealTargetIdx + 1) % JobSystem.INSTANCE.workers.length;

					// Still no work, wait longer on the main work Queue
					handle = JobSystem.INSTANCE.workQueue.poll();

					if(handle == null) {
						inflight.lazySet(false);
						Thread.onSpinWait();
					}
				}

				if(!JobSystem.INSTANCE.active) {
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
		log.debug("Shutdown - {}", JobSystem.INSTANCE.active);
	}

	void processHandle() throws InterruptedException {
		try {
			workerHandleCancel();

			if(handle.item != null && handle.setRunning(this)) {
				inflight.lazySet(true);
				handle.item.onRun();
				handle.item.ranToCompletion.set(true);
			}
		}
		catch (InterruptedException e) {
			log.debug("Interrupt Received whilst processing: {}", handle.hashCode());
		}
		catch (Throwable ex) {
			log.warn("Encountered an error whilst processing: {}", handle.hashCode(), ex);
			handle.cancel(false);
		} finally {
			handle.setCompleted();
			handle.worker = null;
			handle = null;
		}
	}

	void workerHandleCancel() throws InterruptedException {
		if (handle.isCancelled()) {
			if(VALIDATE) log.debug("Handle {} has been cancelled, interrupting to exit execution", handle);
			if(handle.item != null)
				handle.item.onCancel();
			throw new InterruptedException();
		}
	}

	void printState() {
		if(handle == null) {
			log.debug("Worker [{}] Idle", thread.getName());
			return;
		}

		printStacktrace(false, thread.getStackTrace());
	}
}
