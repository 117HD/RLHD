package rs117.hd.utils.jobs;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

public final class JobClientCallback {
	private static final ConcurrentLinkedDeque<JobClientCallback> POOL = new ConcurrentLinkedDeque<>();

	public static JobClientCallback obtain() {
		JobClientCallback callback = POOL.poll();
		if(callback == null)
			callback = new JobClientCallback();
		callback.sema.drainPermits();
		return callback;
	}

	protected final Semaphore sema = new Semaphore(0);
	public Runnable callback;
	public boolean immediate;

	public void release() {
		POOL.add(this);
	}
}
