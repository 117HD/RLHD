package rs117.hd.utils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import rs117.hd.HdPlugin;

public abstract class Job implements Runnable {
	private final ReentrantLock lock = new ReentrantLock();

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

	public void submit() {
		complete(true);

		if (onPrepareCallback != null) {
			onPrepareCallback.callback();
		}

		prepare();

		inFlight = true;
		HdPlugin.THREAD_POOL.execute(this);
	}

	@SneakyThrows
	public void complete(boolean block) {
		if (!inFlight) return;

		if (block) {
			lock.lock();
			lock.unlock();
			onComplete();
			inFlight = false;
		} else {
			if (lock.tryLock(100, TimeUnit.NANOSECONDS)) {
				lock.unlock();
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
			lock.lock();
			doWork();
		} finally {
			lock.unlock();
		}
	}

	protected void prepare() {}

	protected abstract void doWork();

	protected void onComplete() {}
}
