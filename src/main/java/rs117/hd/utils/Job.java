package rs117.hd.utils;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.HdPlugin;

@Slf4j
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

	public void submit() { submit(false); }

	@SneakyThrows
	public void submit(boolean runSynchronously) {
		complete(true);

		try {
			if (onPrepareCallback != null) {
				onPrepareCallback.callback();
			}

			prepare();
		} catch (Exception ex) {
			log.error("Encountered an error whilst processing job: " + getClass().getSimpleName(), ex);
		}

		inFlight = true;

		if (HdPlugin.FORCE_JOBS_RUN_SYNCHRONOUSLY || runSynchronously) {
			run();
		} else {
			completionSema.acquire();
			HdPlugin.THREAD_POOL.execute(this);
		}
	}

	public void complete() { complete(true); }

	@SneakyThrows
	public void complete(boolean block) {
		if (!inFlight) return;

		if (block) {
			completionSema.acquire();
			completionSema.release();
			inFlight = false;
		} else {
			completionSema.acquire();
			if (completionSema.tryAcquire(100, TimeUnit.NANOSECONDS)) {
				completionSema.release();
				inFlight = false;
			}
		}

		try {
			onComplete();
			if (!inFlight && onCompleteCallback != null) {
				onCompleteCallback.callback();
			}
		} catch (Exception ex) {
			log.error("Encountered an error whilst processing job: " + getClass().getSimpleName(), ex);
		}
	}

	@Override
	public void run() {
		try {
			doWork();
		} catch (Exception ex) {
			log.error("Encountered an error whilst processing job: " + getClass().getSimpleName(), ex);
		} finally {
			completionSema.release();
		}
	}

	protected void prepare() {}

	protected abstract void doWork();

	protected void onComplete() {}
}
