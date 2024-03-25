package rs117.hd.utils;

import org.junit.Assert;
import org.junit.Test;

public class AABBTest {
	@Test
	public void testRegionBox() {
		Assert.assertEquals(new AABB(3264, 5120, 3327, 5759), AABB.regionBox(13136, 13145));
		Assert.assertEquals(new AABB(3264, 5120, 3327, 5759), AABB.regionBox(13145, 13136));
		Assert.assertEquals(new AABB(3392, 5760, 3519, 5951), AABB.regionBox(13658, 13916));
	}

	@Test
	public void testRegion() {
		Assert.assertEquals(new AABB(3264, 5696, 3327, 5759), new AABB(13145));
	}
}
