package rs117.hd.utils.jobs;

import com.google.inject.Injector;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class Job {
	static JobSystem JOB_SYSTEM;

	protected final AtomicBoolean done = new AtomicBoolean();
	protected final AtomicBoolean wasCancelled = new AtomicBoolean();
	protected final AtomicBoolean encounteredError = new AtomicBoolean();
	protected final AtomicBoolean ranToCompletion = new AtomicBoolean();
	protected final AtomicBoolean queued = new AtomicBoolean();
	protected JobGroup<Job> group;

	@Getter
	protected boolean isReleased;

	boolean executeAsync = true;
	JobHandle handle;

	public final void waitForCompletion() {
		if (handle != null) {
			try {
				handle.await();
			} catch (InterruptedException e) {
				log.warn("Job {} was interrupted while waiting for completion", this);
				throw new RuntimeException(e);
			} finally {
				handle.release();
			}
		}
	}

	public final boolean isQueued() {
		return queued.get();
	}

	public final boolean encounteredError() {
		return encounteredError.get();
	}

	public final boolean wasCancelled() {
		return wasCancelled.get();
	}

	public final boolean ranToCompletion() {
		return ranToCompletion.get();
	}

	public final void cancel() {
		if (handle != null) {
			try {
				handle.cancel(true);
			} catch (InterruptedException e) {
				log.warn("Job {} was interrupted while waiting for it to be cancelled", this);
				throw new RuntimeException(e);
			} finally {
				handle.release();
			}
		}
	}

	public final void release() {
		if (isReleased)
			return;
		isReleased = true;
		queued.set(false);
		waitForCompletion();
		onReleased();

		if (group != null)
			group.pending.remove(this);
		group = null;
	}

	public final boolean isDone() {
		return done.get();
	}

	public final boolean isHighPriority() {
		return (group != null && group.highPriority) || (handle != null && handle.highPriority);
	}

	protected static Injector getInjector() { return JOB_SYSTEM.injector; }

	protected void invokeClientCallback(boolean immediate, Runnable callback) throws InterruptedException {
		JOB_SYSTEM.invokeClientCallback(immediate || !executeAsync, callback);
	}

	public final void workerHandleCancel() throws InterruptedException {
		if (handle == null)
			return;

		final Worker worker = handle.worker;
		if (handle.worker == null)
			return;

		worker.workerHandleCancel();
	}

	public final <T extends Job> T setExecuteAsync(boolean executeAsync) {
		this.executeAsync = executeAsync;
		return (T) this;
	}

	public final <T extends Job> T queue(JobGroup<T> group, Job... dependencies) {
		assert group != null;
		JOB_SYSTEM.queue(this, group.highPriority, dependencies);
		if (executeAsync) {
			this.group = (JobGroup<Job>) group;
			this.group.pending.add(this);
		}
		return (T) this;
	}

	public final <T extends Job> T queue(boolean highPriority, Job... dependencies) {
		JOB_SYSTEM.queue(this, highPriority, dependencies);
		return (T) this;
	}

	public final <T extends Job> T queue(Job... dependencies) {
		JOB_SYSTEM.queue(this, true, dependencies);
		return (T) this;
	}

	protected abstract void onRun() throws InterruptedException;
	protected abstract void onCancel();
	protected abstract void onReleased();

	public String toString() {
		return "[" + hashCode() + "|" + getClass().getSimpleName() + "]";
	}
}
