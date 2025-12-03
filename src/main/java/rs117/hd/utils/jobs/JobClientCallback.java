package rs117.hd.utils.jobs;

import java.util.concurrent.Semaphore;

public final class JobClientCallback {
	private static final ThreadLocal<JobClientCallback> POOL = new ThreadLocal<>();

	public static JobClientCallback current() {
		JobClientCallback callback = POOL.get();
		if (callback == null)
			callback = new JobClientCallback();
		callback.sema.drainPermits();
		return callback;
	}

	protected final Semaphore sema = new Semaphore(0);
	public Runnable callback;
	public boolean immediate;
}
