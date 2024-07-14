package rs117.hd.utils;

import org.junit.Assert;
import org.junit.Test;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.RegionBox;

public class AABBTest {
	@Test
	public void testAABB() {
		Assert.assertTrue(new AABB(2815, 10097, 0, 2844, 10046, 0)
			.intersects(2832, 10056, 0, 2839, 10063, 0));
		Assert.assertTrue(new AABB(3221, 9602, 3307, 9660)
			.contains(3223, 9617, 0));
	}

	@Test
	public void testRegionBox() {
		Assert.assertEquals(new AABB(3264, 5120, 3327, 5759), new RegionBox(13136, 13145).toAabb());
		Assert.assertEquals(new AABB(3264, 5120, 3327, 5759), new RegionBox(13145, 13136).toAabb());
		Assert.assertEquals(new AABB(3392, 5760, 3519, 5951), new RegionBox(13658, 13916).toAabb());
	}

	@Test
	public void testRegion() {
		Assert.assertEquals(new AABB(3264, 5696, 3327, 5759), new AABB(13145));
	}
}
