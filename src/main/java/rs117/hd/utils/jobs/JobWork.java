package rs117.hd.utils.jobs;

import com.google.inject.Injector;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class JobWork {
	protected final AtomicBoolean done = new AtomicBoolean();
	protected final AtomicBoolean wasCancelled = new AtomicBoolean();
	protected final AtomicBoolean ranToCompletion = new AtomicBoolean();
	protected final AtomicBoolean queued = new AtomicBoolean();
	protected JobGroup<JobWork> group;
	protected boolean isReleased;

	boolean executeAsync = true;
	JobHandle handle;

	public final void waitForCompletion() {
		if(handle != null) {
			try {
				handle.await();
			} catch (InterruptedException e) {
				log.warn("Job {} was interrupted while waiting for completion", this);
				throw new RuntimeException(e);
			} finally {
				handle.release();
			}
		}

		if(group != null) {
			group.pending.remove(this);
			group = null;
		}
	}

	public final boolean isQueued() {
		return queued.get();
	}

	public final boolean wasCancelled() {
		return wasCancelled.get();
	}

	public final boolean ranToCompletion() {
		return ranToCompletion.get();
	}

	public final void cancel() {
		if(handle != null) {
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
		if(isReleased)
			return;
		isReleased = true;
		queued.set(false);
		waitForCompletion();
		onReleased();
	}

	public final boolean isDone() {
		return done.get();
	}

	public final boolean isHighPriority() {
		return (group != null && group.highPriority) || (handle != null && handle.highPriority);
	}

	protected static Injector getInjector() { return JobSystem.INSTANCE.injector; }

	protected void queueClientCallback(boolean immediate, Runnable callback) throws InterruptedException {
		JobSystem.INSTANCE.queueClientCallback(immediate || !executeAsync, callback);
	}

	public final void workerHandleCancel() throws InterruptedException {
		if(handle == null)
			return;

		final JobWorker worker = handle.worker;
		if(handle.worker == null)
			return;

		worker.workerHandleCancel();
	}

	public final <T extends JobWork> T setExecuteAsync(boolean executeAsync) {
		this.executeAsync = executeAsync;
		return (T) this;
	}

	public final <T extends JobWork> T queue(JobGroup<T> group, JobWork... dependencies) {
		assert group != null;
		JobSystem.INSTANCE.queue(this, group.highPriority, dependencies);
		if(executeAsync) {
			this.group = (JobGroup<JobWork>) group;
			this.group.pending.add(this);
		}
		return (T) this;
	}

	public final <T extends JobWork> T queue(boolean highPriority, JobWork... dependencies) {
		JobSystem.INSTANCE.queue(this, highPriority, dependencies);
		return (T) this;
	}

	public final <T extends JobWork> T queue(JobWork... dependencies) {
		JobSystem.INSTANCE.queue(this, true, dependencies);
		return (T) this;
	}

	protected abstract void onRun() throws InterruptedException;
	protected abstract void onCancel();
	protected abstract void onReleased();

	public String toString() {
		return "[" + hashCode() + "|" + getClass().getSimpleName() + "]";
	}
}
