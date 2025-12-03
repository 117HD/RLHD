package rs117.hd.utils.jobs;

import java.util.concurrent.ConcurrentLinkedDeque;

public final class JobGenericTask extends JobWork {
	private static final ConcurrentLinkedDeque<JobGenericTask> POOL = new ConcurrentLinkedDeque<>();

	@FunctionalInterface
	public interface TaskRunnable {
		void run(JobGenericTask Task) throws InterruptedException;
	}

	public static JobGenericTask build(String context, TaskRunnable runnable) {
		JobGenericTask newTask = POOL.poll();
		if (newTask == null)
			newTask = new JobGenericTask();
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
