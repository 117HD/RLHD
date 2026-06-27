package rs117.hd.utils.jobs;

import rs117.hd.utils.collections.ConcurrentPool;

public final class GenericJob extends Job {
	private static final ConcurrentPool<GenericJob> POOL = new ConcurrentPool<>(GenericJob::new);

	@FunctionalInterface
	public interface TaskRunnable {
		void run(GenericJob Task) throws InterruptedException;
	}

	public static GenericJob build(String context, TaskRunnable runnable) {
		GenericJob newTask = POOL.acquire();
		newTask.context = context;
		newTask.runnable = runnable;
		newTask.isReleased = false;

		return newTask;
	}

	public String context;
	public TaskRunnable runnable;

	@Override
	public void onRun() throws InterruptedException {
		runnable.run(this);
	}

	@Override
	protected void onCancel() {}

	@Override
	public void onReleased() {
		runnable = null;
		POOL.recycle(this);
	}

	@Override
	public String toString() {
		return super.toString() + " " + context;
	}
}
