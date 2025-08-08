package rs117.hd.utils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;

@Slf4j
public abstract class Job implements Runnable {
	private final Semaphore completionSema = new Semaphore(1);
	private final AtomicBoolean inFlight = new AtomicBoolean(false); // Used to track if the Async work is being processed
	@Getter
	private boolean isCompleted = true; // Used to track if the job has been marked as completed yet
	private JobCallback onPrepareCallback;
	private JobCallback onCompleteCallback;

	private Job dependsOn = null;

	public interface JobCallback {
		void callback();
	}

	@SneakyThrows
	public Job setDependsOn(Job dependsOn) {
		if (dependsOn != null) {
			// Walk dependency list to see if this will result in a circular dependency
			Job parent = dependsOn;
			int walkAmount = 0;
			final int WalkAmountUpperLimit = 100;
			for (; walkAmount < WalkAmountUpperLimit && parent != null; walkAmount++) {
				if (parent == this) {
					// We've wrapped around to ourselves!
					// To resolve circular dependency, synchronously wait for the dependency to complete
					dependsOn.complete(true);
					return this;
				}

				parent = parent.dependsOn;
			}

			if (walkAmount >= WalkAmountUpperLimit) {
				// Failed to resolve dependency, synchronously wait for the dependency to complete
				dependsOn.complete(true);
				return this;
			}

			this.dependsOn = dependsOn;
		}
		return this;
	}

	public Job setOnPrepareCallback(JobCallback callback) {
		onPrepareCallback = callback;
		return this;
	}

	public Job setOnCompleteCallback(JobCallback callback) {
		onCompleteCallback = callback;
		return this;
	}

	public boolean hasPrepareCallback() { return onPrepareCallback != null; }

	public boolean hasCompleteCallback() { return onCompleteCallback != null; }

	public void submit() { submit(false); }

	@SneakyThrows
	public void submit(boolean runSynchronously) {
		complete(true);

		try {
			if (onPrepareCallback != null) {
				onPrepareCallback.callback();
			}

			prepare();
		} catch (Exception ex) {
			log.error("Encountered an error whilst processing job: " + getClass().getSimpleName(), ex);
		}

		isCompleted = false;
		inFlight.set(true);
		if (HdPlugin.FORCE_JOBS_RUN_SYNCHRONOUSLY || runSynchronously) {
			run();
		} else {
			completionSema.acquire();
			HdPlugin.THREAD_POOL.execute(this);
		}
	}

	public static void completeAll(boolean block, Job... jobs) {
		for(Job job : jobs) {
			if(job != null) {
				job.complete(block);
			}
		}
	}

	public Job wait(boolean block) {
		return wait(block, 100);
	}

	@SneakyThrows
	public Job wait(boolean block, long nano) {
		if (inFlight.get()) {
			if (block) {
				completionSema.acquire();
				completionSema.release();
			} else {
				completionSema.acquire();
				if (completionSema.tryAcquire(nano, TimeUnit.NANOSECONDS)) {
					completionSema.release();
				} else {
					return this;
				}
			}
		}
		return this;
	}

	public Job complete() { return complete(true); }

	@SneakyThrows
	public Job complete(boolean block) {
		if (isCompleted) return this;

		wait(block);

		try {
			onComplete();
			if (onCompleteCallback != null) {
				onCompleteCallback.callback();
			}
		} catch (Exception ex) {
			log.error("Encountered an error whilst processing job: " + getClass().getSimpleName(), ex);
		} finally {
			isCompleted = true;
		}

		return this;
	}

	@Override
	public void run() {
		try {
			if (dependsOn != null) {
				while (dependsOn.inFlight.get()) {
					Thread.yield();
				}
				dependsOn = null;
			}

			doWork();
		} catch (Exception ex) {
			log.error("Encountered an error whilst processing job: " + getClass().getSimpleName(), ex);
		} finally {
			inFlight.set(false);
			completionSema.release();
		}
	}

	protected void prepare() {}

	protected abstract void doWork();

	protected void onComplete() {}
}
