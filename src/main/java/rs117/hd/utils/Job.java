package rs117.hd.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class Job implements Runnable {
	public static boolean FORCE_JOBS_RUN_SYNCHRONOUSLY = false;

	private static final HashSet<Job> CIRCULAR_DEP_SET = new HashSet<>();

	private static int THREAD_POOL_SIZE = 0;
	private static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(
		Math.max(1, Runtime.getRuntime().availableProcessors()),
		(r) -> {
			Thread poolThread = new Thread(r);
			poolThread.setName("117 HD - Job Thread: " + ++THREAD_POOL_SIZE);
			poolThread.setPriority(Thread.NORM_PRIORITY + 3);
			return poolThread;
		}
	);


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

	public boolean hasDependencies() {
		return !dependencies.isEmpty();
	}

	public void clearDependencies() {
		dependencies.clear();
	}

	public Job addDependency(Job dependency) {
		if (dependency == null || dependency == this) {
			throw new IllegalArgumentException("Invalid dependency");
		}

		CIRCULAR_DEP_SET.clear();
		if (hasCircularDependency(dependency)) {
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
		complete(); // If already done before, ensure cleanup

		try {
			if (onPrepareCallback != null) onPrepareCallback.callback();
			onPrepare();
		} catch (Exception ex) {
			log.error("Error in prepare callback for job: " + getClass().getSimpleName(), ex);
		}

		completionSema.acquire();
		inFlight.set(true);
		isCompleted = false;

		if (FORCE_JOBS_RUN_SYNCHRONOUSLY || runSynchronously) {
			run();
		} else {
			THREAD_POOL.execute(this);
		}
	}

	public Job awaitCompletion(boolean block) {
		return awaitCompletion(block, 100);
	}

	@SneakyThrows
	public Job awaitCompletion(boolean block, long nano) {
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
		return complete(true, 0);
	}

	@SneakyThrows
	public Job complete(boolean block, long nano) {
		if (isCompleted) return this;

		awaitCompletion(block,  nano);

		if(isInFlight()) {
			return this;
		}

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
			for (int i = 0; i < dependencies.size(); i++) {
				dependencies.get(i).awaitCompletion(true);
			}
		} catch (Exception ex) {
			log.error("Error while waiting on dependencies: " + getClass().getSimpleName(), ex);

			inFlight.set(false);
			completionSema.release();
			return;
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

	private boolean hasCircularDependency(Job target) {
		if (!CIRCULAR_DEP_SET.add(this)) {
			return true;
		}

		if (this == target) {
			return true;
		}

		for (Job dep : dependencies) {
			if (dep.hasCircularDependency(target)) {
				return true;
			}
		}

		return false;
	}
}
