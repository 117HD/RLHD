package rs117.hd.utils.jobs;

import java.util.concurrent.ConcurrentLinkedDeque;

public final class GenericJob extends Job {
	private static final ConcurrentLinkedDeque<GenericJob> POOL = new ConcurrentLinkedDeque<>();

	@FunctionalInterface
	public interface TaskRunnable {
		void run(GenericJob Task) throws InterruptedException;
	}

	public static GenericJob build(String context, TaskRunnable runnable) {
		GenericJob newTask = POOL.poll();
		if (newTask == null)
			newTask = new GenericJob();
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
		POOL.add(this);
	}

	@Override
	public String toString() {
		return super.toString() + " " + context;
	}
}
