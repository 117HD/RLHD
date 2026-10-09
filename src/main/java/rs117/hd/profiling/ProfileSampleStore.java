package rs117.hd.profiling;

import com.google.inject.Singleton;
import java.util.ArrayDeque;
import lombok.Getter;

@Getter
@Singleton
public class ProfileSampleStore implements Profiler.Listener {
	private final ArrayDeque<ProfileSample> frames = new ArrayDeque<>();

	private boolean capturing = true;

	public void setCapturing(boolean capturing) {
		this.capturing = capturing;
		if (!capturing)
			return;

		// Keep a stable list snapshot while frozen.
		frames.clear();
	}

	public void toggleCapturing() {
		setCapturing(!capturing);
	}

	@Override
	public void onFrameCompletion(ProfileSample timings) {
		if (!capturing)
			return;

		long now = System.currentTimeMillis();
		while (!frames.isEmpty()) {
			if (now - frames.peekFirst().frameTimestamp < 10e3) // remove older entries
				break;
			frames.removeFirst();
		}
		frames.addLast(timings);
	}

	public void clear() {
		frames.clear();
	}
}
