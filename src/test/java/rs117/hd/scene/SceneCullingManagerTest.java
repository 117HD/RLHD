package rs117.hd.scene;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SceneCullingManagerTest {
	@Test
	public void optimiseMergesAFlatGrid() {
		SceneCullingManager manager = new SceneCullingManager();
		SceneCullingManager.CullingResult result = manager.obtainResult();

		result.addAABB(0, 0, 0, 1, 0, 1);
		result.addAABB(1, 0, 0, 2, 0, 1);
		result.addAABB(0, 0, 1, 1, 0, 2);
		result.addAABB(1, 0, 1, 2, 0, 2);

		assertEquals(3, result.optimise());
		result.release();
	}

	@Test
	public void optimiseOnlyMergesDifferentHeightBoxesWithinTheTolerance() {
		SceneCullingManager manager = new SceneCullingManager();
		SceneCullingManager.CullingResult result = manager.obtainResult();

		result.addAABB(0, 0, 0, 1, 1, 1);
		result.addAABB(1, 0.5f, 0, 2, 1.5f, 1);

		assertEquals(0, result.optimise());
		assertEquals(1, result.optimise(0.5f));
		result.release();
	}

	@Test
	public void optimiseRemovesEncapsulatedPrimitives() {
		SceneCullingManager manager = new SceneCullingManager();
		SceneCullingManager.CullingResult result = manager.obtainResult();

		result.addAABB(0, 0, 0, 10, 10, 10);
		result.addAABB(2, 2, 2, 3, 3, 3);
		result.addSphere(5, 5, 5, 1);

		assertEquals(2, result.optimise());
		result.release();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void releasingAPendingResultRemovesItFromTheNextDispatch() throws ReflectiveOperationException {
		SceneCullingManager manager = new SceneCullingManager();
		SceneCullingManager.CullingResult result = manager.obtainResult();
		result.addSphere(0, 0, 0, 1);
		result.queue();
		result.release();

		Field pendingField = SceneCullingManager.class.getDeclaredField("pendingCullingResults");
		pendingField.setAccessible(true);
		assertEquals(0, ((List<SceneCullingManager.CullingResult>) pendingField.get(manager)).size());
	}
}
