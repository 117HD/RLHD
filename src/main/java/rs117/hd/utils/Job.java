package rs117.hd.utils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import rs117.hd.HdPlugin;

public abstract class Job implements Runnable {
	private final Semaphore completionSema = new Semaphore(1);

	@Getter
	private boolean inFlight = false;
	@Setter
	private JobCallback onPrepareCallback;
	@Setter
	private JobCallback onCompleteCallback;

	public interface JobCallback {
		void callback();
	}

	public boolean hasPrepareCallback() { return onPrepareCallback != null; }

	public boolean hasCompleteCallback() { return onCompleteCallback != null; }

	@SneakyThrows
	public void submit() {
		complete(true);

		if (onPrepareCallback != null) {
			onPrepareCallback.callback();
		}

		prepare();

		inFlight = true;

		if (HdPlugin.FORCE_JOBS_RUN_SYNCHRONOUSLY) {
			doWork();
		} else {
			completionSema.acquire();
			HdPlugin.THREAD_POOL.execute(this);
		}
	}

	@SneakyThrows
	public void complete(boolean block) {
		if (!inFlight) return;

		if (block) {
			completionSema.acquire();
			completionSema.release();
			onComplete();
			inFlight = false;
		} else {
			completionSema.acquire();
			if (completionSema.tryAcquire(100, TimeUnit.NANOSECONDS)) {
				completionSema.release();
				onComplete();
				inFlight = false;
			}
		}

		if (!inFlight && onCompleteCallback != null) {
			onCompleteCallback.callback();
		}
	}

	@Override
	public void run() {
		try {
			doWork();
		} finally {
			completionSema.release();
		}
	}

	protected void prepare() {}

	protected abstract void doWork();

	protected void onComplete() {}
}
