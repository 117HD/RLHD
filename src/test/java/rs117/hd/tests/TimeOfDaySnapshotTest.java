package rs117.hd.tests;

import org.junit.Test;
import rs117.hd.scene.TimeOfDay;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class TimeOfDaySnapshotTest {
	@Test
	public void sunAnglesAreCachedWithinAFrameAndInvalidatedByUpdate() {
		TimeOfDay tod = new TimeOfDay();
		// update() is the per-frame entry point: it pins the instant every getter
		// derives from and drops the previous frame's astronomy snapshot.
		tod.update();
		double[] first = tod.getSunAngles();
		double[] second = tod.getSunAngles();
		assertSame("within one frame, getSunAngles must return the cached array", first, second);

		tod.update();
		double[] third = tod.getSunAngles();
		assertNotSame("update must invalidate the cache", first, third);
	}
}
