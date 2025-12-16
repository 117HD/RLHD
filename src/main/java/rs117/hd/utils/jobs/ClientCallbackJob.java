package rs117.hd.utils.jobs;

import java.util.concurrent.Semaphore;

public final class ClientCallbackJob {
	private static final ThreadLocal<ClientCallbackJob> POOL = ThreadLocal.withInitial(ClientCallbackJob::new);

	public static ClientCallbackJob current() {
		ClientCallbackJob callback = POOL.get();
		callback.semaphore.drainPermits();
		return callback;
	}

	final Semaphore semaphore = new Semaphore(0);
	public Runnable callback;
	public boolean immediate;
}
