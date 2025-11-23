package rs117.hd.tests;

import java.util.Arrays;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import net.runelite.api.coords.WorldPoint;
import rs117.hd.scene.areas.Polygon;

public class PolygonTest {
	// Test polygon points
	private static final WorldPoint[] POLYGON_POINTS = {
		new WorldPoint(3199, 3501, 0),
		new WorldPoint(3221, 3501, 0),
		new WorldPoint(3228, 3496, 0),
		new WorldPoint(3230, 3488, 0),
		new WorldPoint(3231, 3471, 0),
		new WorldPoint(3228, 3456, 0),
		new WorldPoint(3218, 3454, 0),
		new WorldPoint(3204, 3454, 0),
		new WorldPoint(3198, 3455, 0),
		new WorldPoint(3197, 3470, 0),
		new WorldPoint(3197, 3486, 0),
		new WorldPoint(3196, 3491, 0),
		new WorldPoint(3198, 3496, 0)
	};

	@Test
	public void testPolygonContainment() {
		// Convert array to List for constructor
		List<WorldPoint> pointsList = Arrays.asList(POLYGON_POINTS);
		Polygon polygon = new Polygon(pointsList);

		// Test point that should be inside the polygon
		int testX = 3209;
		int testY = 3474;
		int testZ = 0;

		// Test with int coordinates
		boolean containsInt = polygon.contains(testX, testY, testZ);
		
		Assert.assertTrue(
			String.format("Point (%d, %d, %d) should be inside the polygon", testX, testY, testZ),
			containsInt
		);
	}

}

