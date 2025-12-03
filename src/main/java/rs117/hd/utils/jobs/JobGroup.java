package rs117.hd.utils.jobs;

import java.util.concurrent.LinkedBlockingDeque;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class JobGroup<T extends Job> {
	@Getter
	protected final LinkedBlockingDeque<T> pending = new LinkedBlockingDeque<>();

	@Getter
	protected final boolean highPriority;

	@Getter
	protected final boolean autoRelease;

	public int getPendingCount() { return pending.size(); }

	public void complete() {
		T work;
		while ((work = pending.poll()) != null) {
			work.waitForCompletion();
			if (autoRelease) work.release();
		}
	}

	public void cancel() {
		T work;
		while ((work = pending.poll()) != null) {
			work.cancel();
			if (autoRelease) work.release();
		}
		pending.clear();
	}
}
