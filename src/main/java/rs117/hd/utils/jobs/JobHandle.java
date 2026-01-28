package rs117.hd.utils.jobs;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static rs117.hd.utils.HDUtils.getThreadStackTrace;
import static rs117.hd.utils.jobs.JobSystem.VALIDATE;

@Slf4j
final class JobHandle extends AbstractQueuedSynchronizer {
	static JobSystem JOB_SYSTEM;

	public static final int STATE_NONE = 0;
	public static final int STATE_QUEUED = 1;
	public static final int STATE_RUNNING = 2;
	public static final int STATE_CANCELLED = 3;
	public static final int STATE_COMPLETED = 4;

	private static final String[] STATE_NAMES = { "NONE", "QUEUED", "RUNNING", "CANCELLED", "COMPLETED" };
	private static final long DEADLOCK_TIMEOUT_SECONDS = 10;
	private static final ConcurrentLinkedDeque<JobHandle> POOL = new ConcurrentLinkedDeque<>();

	private static final ThreadLocal<ArrayDeque<JobHandle>> CYCLE_STACK = ThreadLocal.withInitial(ArrayDeque::new);
	private static final ThreadLocal<HashSet<JobHandle>> VISITED = ThreadLocal.withInitial(HashSet::new);

	private final LinkedBlockingDeque<JobHandle> dependants = new LinkedBlockingDeque<>();
	private final AtomicInteger jobState = new AtomicInteger(STATE_NONE);
	private final AtomicInteger refCounter = new AtomicInteger();
	private final AtomicInteger depCount = new AtomicInteger();

	@Getter
	Job item;
	@Getter
	Worker worker;
	@Getter
	boolean highPriority;

	static JobHandle obtain() {
		JobHandle handle = POOL.poll();
		if (handle == null || handle.refCounter.get() > 0) {
			if (handle != null) {
				POOL.add(handle); // Re-add to the end of the pool
			}
			handle = new JobHandle();
		}

		handle.setJobState(STATE_NONE);
		handle.refCounter.set(1);
		handle.dependants.clear();

		// reset AQS state for completion
		handle.setStateAQS(0);

		handle.depCount.set(0);
		handle.highPriority = false;
		handle.item = null;
		handle.worker = null;

		return handle;
	}

	synchronized boolean addDependency(@Nullable JobHandle handle) {
		return handle != null && handle.addDependant(this);
	}

	synchronized boolean addDependant(@Nullable JobHandle handle) {
		if (handle == null)
			return false;

		if (isCompleted() || isReleased()) {
			if (VALIDATE)
				log.debug(
					"Handle [{}] Skipping dependant [{}] due to being in state: [{}]",
					this,
					handle,
					STATE_NAMES[jobState.get()]
				);
			return false;
		}

		if (wouldCreateCycle(handle, this))
			throw new IllegalStateException("Circular dependency detected: " + this + " depends on " + handle);

		if (VALIDATE)
			log.debug("Handle [{}] added dependant [{}]", this, handle);

		dependants.add(handle);
		handle.depCount.getAndIncrement();

		return true;
	}

	static boolean wouldCreateCycle(JobHandle start, JobHandle target) {
		if (start == target)
			return true;

		if (start.dependants.isEmpty())
			return false;

		ArrayDeque<JobHandle> stack = CYCLE_STACK.get();
		HashSet<JobHandle> visited = VISITED.get();
		try {
			stack.push(start);
			while (!stack.isEmpty()) {
				JobHandle h = stack.pop();
				if (!visited.add(h)) continue;
				if (h == target) return true;
				for (JobHandle dep : h.dependants) stack.push(dep);
			}
		} finally {
			stack.clear();
			visited.clear();
		}

		return false;
	}

	synchronized boolean setRunning(Worker worker) {
		if (isInQueue()) {
			setJobState(STATE_RUNNING);
			this.worker = worker;
			return true;
		}
		return false;
	}

	synchronized void setInQueue() {
		assert isIdle() : "State should be NONE but is " + STATE_NAMES[jobState.get()];
		setJobState(STATE_QUEUED);
	}

	synchronized void setCompleted() throws InterruptedException {
		if (isCompleted()) return;

		final boolean wasCancelled = isCancelled();
		setJobState(STATE_COMPLETED);

		if (item != null)
			item.done.set(true);

		// Signal completion via AQS
		releaseShared(0);

		if (item != null)
			item.onCompletion();

		if (VALIDATE)
			log.debug("Handle [{}] Completed", this);

		int queuedWork = 0;
		JobHandle dep;
		while ((dep = dependants.pollFirst()) != null) {
			if (wasCancelled) {
				dep.cancel(false);
				continue;
			}

			if (dep.isIdle() && dep.depCount.decrementAndGet() == 0) {
				dep.setInQueue();
				if (VALIDATE)
					log.debug("Handle [{}] Adding: [{}] to queue", this, dep);

				if (dep.isHighPriority()) {
					worker.localWorkQueue.addFirst(dep);
				} else {
					worker.localWorkQueue.addLast(dep);
				}

				queuedWork++;
			}
		}

		if (queuedWork > 1)
			JOB_SYSTEM.signalWorkAvailable(queuedWork - 1);
	}

	private void setJobState(int newState) {
		final int currentState = jobState.get();
		if (currentState == newState) return;
		if (VALIDATE) log.trace("[{}] {} -> {}", hashCode(), STATE_NAMES[currentState], STATE_NAMES[newState]);
		jobState.set(newState);
	}

	private void setStateAQS(int value) {
		setState(value); // AQS state for completion: 0 = not done, 1 = done
	}

	synchronized void release() {
		if (isReleased()) return;

		if (refCounter.decrementAndGet() > 0)
			return;

		assert item != null : "Double Release, item is already null";
		assert isCompleted() : "Release before setCompleted() has been called?!";
		assert !VALIDATE || !POOL.contains(this) : "POOL already contains this Handle?!";

		if (VALIDATE) log.debug("Releasing [{}] state: [{}]", this, STATE_NAMES[jobState.get()]);
		setJobState(STATE_NONE);
		item.handle = null;
		item = null;
		worker = null;

		POOL.add(this);
	}

	void cancel(boolean block) throws InterruptedException {
		if (item == null || isCancelled() || isCompleted())
			return;

		int prevState = jobState.get();
		setJobState(STATE_CANCELLED);

		if (item != null)
			item.wasCancelled.set(true);

		if (VALIDATE) log.debug("Cancelling [{}] state: [{}]", this, STATE_NAMES[prevState]);

		if (prevState == STATE_NONE || (prevState == STATE_QUEUED && JOB_SYSTEM.workQueue.remove(this))) {
			setCompleted();
			return;
		}

		if (prevState == STATE_RUNNING && worker != null && worker.thread != Thread.currentThread())
			worker.thread.interrupt();

		if (block)
			await();
	}

	boolean isReleased() { return isIdle() && refCounter.get() == 0; }
	boolean isIdle() { return jobState.get() == STATE_NONE; }
	boolean isInQueue() { return jobState.get() == STATE_QUEUED; }
	boolean isCancelled() { return jobState.get() == STATE_CANCELLED; }
	boolean isCompleted() { return jobState.get() == STATE_COMPLETED; }

	boolean await() throws InterruptedException {
		return await(-1);
	}

	boolean await(int timeoutNs) throws InterruptedException {
		refCounter.incrementAndGet();

		final boolean isClientThread = JOB_SYSTEM.client != null && JOB_SYSTEM.client.isClientThread();
		try {
			if (!isDone()) {
				if (isClientThread) {
					long start = System.currentTimeMillis();
					int seconds = 0;
					while (!tryAcquireSharedNanos(0, TimeUnit.MILLISECONDS.toNanos(1))) {
						JOB_SYSTEM.processPendingClientCallbacks();
						Thread.yield();
						long elapsed = System.currentTimeMillis() - start;
						int newSeconds = (int) (elapsed / 1000);
						if (newSeconds > seconds) {
							if (VALIDATE) {
								log.debug(
									"Waiting on Handle: [{}] state [{}] elapsed: {} secs",
									this,
									STATE_NAMES[jobState.get()],
									newSeconds
								);
								JOB_SYSTEM.printWorkersState();
							}
							seconds = newSeconds;
						}
						if(timeoutNs > 0 && elapsed > timeoutNs) {
							return false;
						} else {
							if (elapsed > DEADLOCK_TIMEOUT_SECONDS * 1000) {
								handleDeadlock();
								return false;
							}
						}
					}
				} else {
					if(timeoutNs > 0) {
						if (!tryAcquireSharedNanos(0, timeoutNs))
							return false;
					} else {
						if (!tryAcquireSharedNanos(0, TimeUnit.SECONDS.toNanos(DEADLOCK_TIMEOUT_SECONDS)))
							handleDeadlock();
					}
				}
			}
		} finally {
			refCounter.decrementAndGet();
		}
		return true;
	}

	private boolean isDone() {
		return getState() != 0;
	}

	@Override
	protected int tryAcquireShared(int ignored) {
		return isDone() ? 1 : -1;
	}

	@Override
	protected boolean tryReleaseShared(int ignored) {
		setStateAQS(1);
		return true;
	}

	private void handleDeadlock() throws InterruptedException {
		if (!JOB_SYSTEM.active) return;

		log.warn(
			"Deadlock detected on thread: {} whilst waiting {} seconds on handle {} {}, worker {}, shutting down...",
			Thread.currentThread().getName(),
			DEADLOCK_TIMEOUT_SECONDS,
			hashCode(),
			item,
			worker
		);
		log.warn("Thread {} stacktrace:\n{}", Thread.currentThread().getName(), getThreadStackTrace(Thread.currentThread()));
		if (worker != null)
			log.warn("Worker {} stacktrace:\n{}", worker.thread.getName(), getThreadStackTrace(worker.thread));

		JOB_SYSTEM.plugin.stopPlugin();
		if (JOB_SYSTEM.isWorker())
			throw new InterruptedException();
	}

	@Override
	public String toString() {
		return "[" + hashCode() + "] " + (item != null ? item.toString() : "null");
	}
}
