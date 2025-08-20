package rs117.hd.utils;

import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rs117.hd.HdPlugin;

@Slf4j
public abstract class Job implements Runnable {

	private final Semaphore completionSema = new Semaphore(1);
	private final AtomicBoolean inFlight = new AtomicBoolean(false);

	private final List<Job> dependencies = new ArrayList<>();

	private JobCallback onPrepareCallback;
	private JobCallback onCompleteCallback;

	@Getter
	private boolean isCompleted = true;

	public interface JobCallback {
		void callback();
	}

	public Job setOnPrepareCallback(JobCallback callback) {
		this.onPrepareCallback = callback;
		return this;
	}

	public Job setOnCompleteCallback(JobCallback callback) {
		this.onCompleteCallback = callback;
		return this;
	}

	public Job addDependency(Job dependency) {
		if (dependency == null || dependency == this) {
			throw new IllegalArgumentException("Invalid dependency");
		}

		if (hasCircularDependency(dependency, new HashSet<>())) {
			throw new IllegalStateException("Circular dependency detected between " +
											this.getClass().getSimpleName() + " and " + dependency.getClass().getSimpleName());
		}

		this.dependencies.add(dependency);
		return this;
	}

	public boolean hasPrepareCallback() {
		return onPrepareCallback != null;
	}

	public boolean hasCompleteCallback() {
		return onCompleteCallback != null;
	}

	public boolean isInFlight() {
		return inFlight.get();
	}

	public void submit() {
		submit(false);
	}

	@SneakyThrows
	public void submit(boolean runSynchronously) {
		complete(true); // If already done before, ensure cleanup

		try {
			if (onPrepareCallback != null) onPrepareCallback.callback();
			onPrepare();
		} catch (Exception ex) {
			log.error("Error in prepare callback for job: " + getClass().getSimpleName(), ex);
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

	public Job wait(boolean block) {
		return wait(block, 100);
	}

	@SneakyThrows
	public Job wait(boolean block, long nano) {
		if (inFlight.get()) {
			if (block) {
				completionSema.acquire();
				completionSema.release();
			} else if (completionSema.tryAcquire(nano, TimeUnit.NANOSECONDS)) {
				completionSema.release();
			}
		}
		return this;
	}

	public Job complete() {
		return complete(true);
	}

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
			log.error("Error in complete callback for job: " + getClass().getSimpleName(), ex);
		} finally {
			isCompleted = true;
		}

		return this;
	}

	@Override
	public void run() {
		try {
			for (Job dep : dependencies) {
				dep.wait(true);
			}
		} catch (Exception ex) {
			log.error("Error while waiting on dependencies: " + getClass().getSimpleName(), ex);

			inFlight.set(false);
			completionSema.release();

			return;
		} finally {
			dependencies.clear();
		}

		try {
			doWork();
		} catch (Exception ex) {
			log.error("Error while running job: " + getClass().getSimpleName(), ex);
		} finally {
			inFlight.set(false);
			completionSema.release();
		}
	}

	protected void onPrepare() {
	}

	protected abstract void doWork();

	protected void onComplete() {
	}

	private boolean hasCircularDependency(Job target, Set<Job> visited) {
		if (!visited.add(this)) {
			return false;
		}

		if (this == target) {
			return true;
		}

		for (Job dep : dependencies) {
			if (dep.hasCircularDependency(target, visited)) {
				return true;
			}
		}

		return false;
	}
}
