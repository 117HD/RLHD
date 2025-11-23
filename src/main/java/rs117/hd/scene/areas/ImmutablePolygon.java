package rs117.hd.scene.areas;

import java.awt.Polygon;
import java.awt.Rectangle;

public class ImmutablePolygon extends Polygon {
	private final int minX, minY, maxX, maxY;
	private final int[] xPointsArray;
	private final int[] yPointsArray;
	private final int nPoints;
	private Rectangle immutableBoundingBox;

	public ImmutablePolygon(int[] xPoints, int[] yPoints, int nPoints) {
		super(xPoints, yPoints, nPoints);
		this.nPoints = nPoints;
		this.xPointsArray = new int[nPoints];
		this.yPointsArray = new int[nPoints];
		System.arraycopy(xPoints, 0, xPointsArray, 0, nPoints);
		System.arraycopy(yPoints, 0, yPointsArray, 0, nPoints);
		
		// Precompute bounding box for O(1) rejection
		int minX = xPoints[0], maxX = xPoints[0];
		int minY = yPoints[0], maxY = yPoints[0];
		for (int i = 1; i < nPoints; i++) {
			int x = xPoints[i];
			int y = yPoints[i];
			if (x < minX) minX = x;
			else if (x > maxX) maxX = x;
			if (y < minY) minY = y;
			else if (y > maxY) maxY = y;
		}
		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
	}

	@Override
	public void addPoint(int x, int y) {
		throw new IllegalStateException("Polygon is immutable.");
	}

	// Ray casting - works for both winding orders, pure primitives
	public boolean containsFast(int x, int y) {
		if (x < minX || x > maxX || y < minY || y > maxY) {
			return false;
		}

		boolean inside = false;
		int j = nPoints - 1;
		
		for (int i = 0; i < nPoints; i++) {
			int xi = xPointsArray[i];
			int yi = yPointsArray[i];
			int xj = xPointsArray[j];
			int yj = yPointsArray[j];

			if ((yi > y) != (yj > y)) {
				long dx = (long) xj - xi;
				long dy = (long) yj - yi;
				long cross = dx * ((long) y - yi) - ((long) x - xi) * dy;
				
				if (dy != 0 && (cross > 0) == (dy < 0)) {
					inside = !inside;
				}
			}
			j = i;
		}
		
		return inside;
	}

	/**
	 * @deprecated Deprecated in Java
	 */
	@Deprecated
	@Override
	@SuppressWarnings("deprecation")
	public Rectangle getBoundingBox() {
		if (immutableBoundingBox == null) {
			immutableBoundingBox = super.getBoundingBox();
		}
		return immutableBoundingBox;
	}
}