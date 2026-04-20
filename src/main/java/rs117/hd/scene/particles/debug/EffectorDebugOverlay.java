package rs117.hd.scene.particles.debug;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.effector.EffectorDefinition;
import rs117.hd.scene.particles.effector.EffectorDefinitionManager;
import rs117.hd.scene.particles.effector.EffectorPlacement;
import rs117.hd.utils.Mat4;

import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.utils.MathUtils.round;

@Singleton
public class EffectorDebugOverlay extends Overlay {
	private static final Color DOME_LINE_COLOR = new Color(80, 255, 255, 255);
	private static final Color DOME_FILL_COLOR = new Color(80, 255, 255, 60);
	private static final Color DOME_RING_COLOR = new Color(90, 245, 255, 255);
	private static final Color DOME_MERIDIAN_COLOR = new Color(70, 220, 245, 255);
	private static final Color CENTER_LINE_COLOR = new Color(0, 220, 255, 200);
	private static final Color ARROW_COLOR = new Color(0, 255, 160, 230);

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private EffectorDefinitionManager effectorDefinitionManager;

	public EffectorDebugOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean active) {
		if (active) overlayManager.add(this);
		else overlayManager.remove(this);
	}

	@Override
	public Dimension render(Graphics2D g) {
		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null) return null;

		int currentPlane = client.getTopLevelWorldView() != null ? client.getTopLevelWorldView().getPlane() : 0;
		float[] proj = buildProjection();
		float[] pt = new float[4];
		int[] local = new int[3];

		g.setStroke(new BasicStroke(3f));
		for (EffectorPlacement placement : effectorDefinitionManager.getPlacements()) {
			if (placement.getPlane() != currentPlane) continue;

			EffectorDefinition def = effectorDefinitionManager.getDefinition(placement.getEffectorId());
			if (def == null) continue;

			WorldPoint wp = new WorldPoint(placement.getWorldX(), placement.getWorldY(), placement.getPlane());
			int[] loc = ctx.worldToLocalFirst(wp, local);
			if (loc == null) continue;

			float centerLocalX = loc[0] + LOCAL_TILE_SIZE / 2f;
			float centerLocalZ = loc[1] + LOCAL_TILE_SIZE / 2f;
			float baseX = centerLocalX;
			float baseZ = centerLocalZ;
			// Match emitter semantics: offset is from tile height (positive raises the source).
			float baseY = ParticleManager.getTerrainHeight(ctx, (int) centerLocalX, (int) centerLocalZ, loc[2]) - def.heightOffset;
			float radius = computeDebugRadius(def);
			float halfAngle = getHalfAngle(def);

			float[] dir = getDisplayDirection(def);
			drawHemisphereWire(g, proj, pt, baseX, baseY, baseZ, radius, halfAngle, dir[0], dir[1], dir[2]);
			drawForceArrow(g, proj, pt, baseX, baseY, baseZ, radius, dir[0], dir[1], dir[2]);
		}
		return null;
	}

	private float computeDebugRadius(EffectorDefinition def) {
		float r;
		if (def.falloffType == 0 || def.maxRange >= Integer.MAX_VALUE / 2L) {
			r = Math.abs(def.forceMagnitude) * 0.8f;
		} else {
			r = (float) Math.sqrt(Math.max(1L, def.maxRange)) / 16f;
		}
		return Math.max(24f, Math.min(300f, r));
	}

	private float getHalfAngle(EffectorDefinition def) {
		// Match 2011 client logic exactly:
		// coneAngleCosine = cosine[spreadAngle << 3], then dot >= coneAngleCosine
		float cos = Math.max(-1f, Math.min(1f, def.coneAngleCosine / 16384f));
		return (float) Math.acos(cos);
	}

	private float[] buildProjection() {
		float[] proj = Mat4.identity();
		int vw, vh, vx, vy;
		if (plugin.sceneViewport != null) {
			vx = plugin.sceneViewport[0];
			vy = plugin.sceneViewport[1];
			vw = plugin.sceneViewport[2];
			vh = plugin.sceneViewport[3];
		} else {
			vx = client.getViewportXOffset();
			vy = client.getViewportYOffset();
			vw = client.getViewportWidth();
			vh = client.getViewportHeight();
		}
		Mat4.mul(proj, Mat4.translate(vx, vy, 0));
		Mat4.mul(proj, Mat4.scale(vw, vh, 1));
		Mat4.mul(proj, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(proj, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(proj, plugin.viewProjMatrix);
		return proj;
	}

	private void drawHemisphereWire(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float radius, float halfAngle, float nx, float ny, float nz) {
		final int latSegments = 7;
		final int lonSegments = 18;
		float[] basis = buildBasis(nx, ny, nz);
		float ux = basis[0], uy = basis[1], uz = basis[2];
		float vx = basis[3], vy = basis[4], vz = basis[5];
		float fullHalf = (float) (Math.PI / 2.0);
		float capAxis = (float) (radius * Math.cos(halfAngle));
		float capRadius = (float) (radius * Math.sin(halfAngle));
		float capX = cx + nx * capAxis;
		float capY = cy + ny * capAxis;
		float capZ = cz + nz * capAxis;

		g.setColor(DOME_FILL_COLOR);
		// Keep overall shell size fixed; spread only affects the flat cap position/radius.
		fillHemisphereSurfaceOriented(g, proj, pt, cx, cy, cz, radius, fullHalf, latSegments, lonSegments, nx, ny, nz, ux, uy, uz, vx, vy, vz);
		// End-cap disc like the reference gizmo.
		fillRingOriented(g, proj, pt, capX, capY, capZ, capRadius, lonSegments, ux, uy, uz, vx, vy, vz);

		g.setColor(DOME_RING_COLOR);
		for (int i = 1; i <= latSegments; i++) {
			double t = i / (double) latSegments;
			double phi = t * fullHalf;
			float axis = (float) (radius * Math.cos(phi));
			float ringR = (float) (radius * Math.sin(phi));
			float rcx = cx + nx * axis;
			float rcy = cy + ny * axis;
			float rcz = cz + nz * axis;
			drawRingOriented(g, proj, pt, rcx, rcy, rcz, ringR, lonSegments, ux, uy, uz, vx, vy, vz);
		}

		g.setColor(DOME_MERIDIAN_COLOR);
		for (int j = 0; j < lonSegments; j++) {
			double theta = j * (Math.PI * 2.0 / lonSegments);
			drawMeridianOriented(g, proj, pt, cx, cy, cz, radius, fullHalf, theta, nx, ny, nz, ux, uy, uz, vx, vy, vz);
		}

		// Connect the outer end ring edges to the bottom center.
		g.setColor(DOME_LINE_COLOR);
		for (int j = 0; j < lonSegments; j += 2) {
			double a = j * (Math.PI * 2.0 / lonSegments);
			float c = (float) Math.cos(a);
			float s = (float) Math.sin(a);
			float ex = capX + (ux * c + vx * s) * capRadius;
			float ey = capY + (uy * c + vy * s) * capRadius;
			float ez = capZ + (uz * c + vz * s) * capRadius;
			drawLine3d(g, proj, pt, cx, cy, cz, ex, ey, ez);
		}

		g.setColor(CENTER_LINE_COLOR);
		drawLine3d(g, proj, pt, cx, cy, cz, cx + nx * radius, cy + ny * radius, cz + nz * radius);
	}

	private void drawForceArrow(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float radius, float dx, float dy, float dz) {
		float arrowLen = Math.max(80f, radius * 0.75f);
		float ex = cx + dx * arrowLen;
		float ey = cy + dy * arrowLen;
		float ez = cz + dz * arrowLen;

		g.setColor(ARROW_COLOR);
		g.setStroke(new BasicStroke(2f));
		drawLine3d(g, proj, pt, cx, cy, cz, ex, ey, ez);

		float hx = ex - dx * 18f;
		float hy = ey - dy * 18f;
		float hz = ez - dz * 18f;

		float ux = -dz;
		float uy = 0f;
		float uz = dx;
		float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
		if (ul < 0.001f) {
			ux = 1f;
			uy = 0f;
			uz = 0f;
			ul = 1f;
		}
		ux /= ul;
		uy /= ul;
		uz /= ul;

		float lx = hx + ux * 7f;
		float ly = hy + uy * 7f;
		float lz = hz + uz * 7f;
		float rx = hx - ux * 7f;
		float ry = hy - uy * 7f;
		float rz = hz - uz * 7f;
		drawLine3d(g, proj, pt, ex, ey, ez, lx, ly, lz);
		drawLine3d(g, proj, pt, ex, ey, ez, rx, ry, rz);
	}

	private void drawRingOriented(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float r, int segments,
		float ux, float uy, float uz, float vx, float vy, float vz) {
		int prevX = 0, prevY = 0;
		boolean prevOk = false;
		for (int i = 0; i <= segments; i++) {
			double a = i * (Math.PI * 2.0 / segments);
			float c = (float) Math.cos(a);
			float s = (float) Math.sin(a);
			float x = cx + (ux * c + vx * s) * r;
			float y = cy + (uy * c + vy * s) * r;
			float z = cz + (uz * c + vz * s) * r;
			int sx = projectX(proj, pt, x, y, z);
			int sy = projectY(proj, pt, x, y, z);
			boolean ok = sx != Integer.MIN_VALUE;
			if (ok && prevOk) g.drawLine(prevX, prevY, sx, sy);
			prevX = sx;
			prevY = sy;
			prevOk = ok;
		}
	}

	private void fillRingOriented(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float r, int segments,
		float ux, float uy, float uz, float vx, float vy, float vz) {
		Polygon poly = new Polygon();
		for (int i = 0; i <= segments; i++) {
			double a = i * (Math.PI * 2.0 / segments);
			float c = (float) Math.cos(a);
			float s = (float) Math.sin(a);
			float x = cx + (ux * c + vx * s) * r;
			float y = cy + (uy * c + vy * s) * r;
			float z = cz + (uz * c + vz * s) * r;
			int sx = projectX(proj, pt, x, y, z);
			int sy = projectY(proj, pt, x, y, z);
			if (sx != Integer.MIN_VALUE) {
				poly.addPoint(sx, sy);
			}
		}
		if (poly.npoints >= 3) {
			g.fillPolygon(poly);
		}
	}

	private void fillSideFanOriented(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float radius, int segments,
		float ux, float uy, float uz, float vx, float vy, float vz) {
		int csx = projectX(proj, pt, cx, cy, cz);
		int csy = projectY(proj, pt, cx, cy, cz);
		if (csx == Integer.MIN_VALUE) {
			return;
		}

		int[] xs = new int[segments + 1];
		int[] ys = new int[segments + 1];
		boolean[] ok = new boolean[segments + 1];
		for (int i = 0; i <= segments; i++) {
			double a = i * (Math.PI * 2.0 / segments);
			float c = (float) Math.cos(a);
			float s = (float) Math.sin(a);
			float x = cx + (ux * c + vx * s) * radius;
			float y = cy + (uy * c + vy * s) * radius;
			float z = cz + (uz * c + vz * s) * radius;
			int sx = projectX(proj, pt, x, y, z);
			int sy = projectY(proj, pt, x, y, z);
			xs[i] = sx;
			ys[i] = sy;
			ok[i] = sx != Integer.MIN_VALUE;
		}

		for (int i = 0; i < segments; i++) {
			if (!ok[i] || !ok[i + 1]) {
				continue;
			}
			Polygon tri = new Polygon();
			tri.addPoint(csx, csy);
			tri.addPoint(xs[i], ys[i]);
			tri.addPoint(xs[i + 1], ys[i + 1]);
			g.fillPolygon(tri);
		}
	}

	private void fillHemisphereSurfaceOriented(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float radius, float halfAngle,
		int latSegments, int lonSegments, float nx, float ny, float nz, float ux, float uy, float uz, float vx, float vy, float vz) {
		for (int lat = 0; lat < latSegments; lat++) {
			double t0 = lat / (double) latSegments;
			double t1 = (lat + 1) / (double) latSegments;
			double phi0 = t0 * halfAngle;
			double phi1 = t1 * halfAngle;
			float axis0 = (float) (radius * Math.cos(phi0));
			float axis1 = (float) (radius * Math.cos(phi1));
			float r0 = (float) (radius * Math.sin(phi0));
			float r1 = (float) (radius * Math.sin(phi1));

			for (int lon = 0; lon < lonSegments; lon++) {
				double a0 = lon * (Math.PI * 2.0 / lonSegments);
				double a1 = (lon + 1) * (Math.PI * 2.0 / lonSegments);
				float c0 = (float) Math.cos(a0);
				float s0 = (float) Math.sin(a0);
				float c1 = (float) Math.cos(a1);
				float s1 = (float) Math.sin(a1);

				float p0x = cx + nx * axis0 + (ux * c0 + vx * s0) * r0;
				float p0y = cy + ny * axis0 + (uy * c0 + vy * s0) * r0;
				float p0z = cz + nz * axis0 + (uz * c0 + vz * s0) * r0;
				float p1x = cx + nx * axis0 + (ux * c1 + vx * s1) * r0;
				float p1y = cy + ny * axis0 + (uy * c1 + vy * s1) * r0;
				float p1z = cz + nz * axis0 + (uz * c1 + vz * s1) * r0;
				float p2x = cx + nx * axis1 + (ux * c1 + vx * s1) * r1;
				float p2y = cy + ny * axis1 + (uy * c1 + vy * s1) * r1;
				float p2z = cz + nz * axis1 + (uz * c1 + vz * s1) * r1;
				float p3x = cx + nx * axis1 + (ux * c0 + vx * s0) * r1;
				float p3y = cy + ny * axis1 + (uy * c0 + vy * s0) * r1;
				float p3z = cz + nz * axis1 + (uz * c0 + vz * s0) * r1;

				int s0x = projectX(proj, pt, p0x, p0y, p0z);
				int s0y = projectY(proj, pt, p0x, p0y, p0z);
				int s1x = projectX(proj, pt, p1x, p1y, p1z);
				int s1y = projectY(proj, pt, p1x, p1y, p1z);
				int s2x = projectX(proj, pt, p2x, p2y, p2z);
				int s2y = projectY(proj, pt, p2x, p2y, p2z);
				int s3x = projectX(proj, pt, p3x, p3y, p3z);
				int s3y = projectY(proj, pt, p3x, p3y, p3z);
				if (s0x == Integer.MIN_VALUE || s1x == Integer.MIN_VALUE || s2x == Integer.MIN_VALUE || s3x == Integer.MIN_VALUE) {
					continue;
				}

				Polygon quad = new Polygon();
				quad.addPoint(s0x, s0y);
				quad.addPoint(s1x, s1y);
				quad.addPoint(s2x, s2y);
				quad.addPoint(s3x, s3y);
				g.fillPolygon(quad);
			}
		}
	}

	private void drawMeridianOriented(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float r, float halfAngle, double theta,
		float nx, float ny, float nz, float ux, float uy, float uz, float vx, float vy, float vz) {
		int prevX = Integer.MIN_VALUE;
		int prevY = Integer.MIN_VALUE;
		for (int i = 0; i <= 16; i++) {
			double t = i / 16.0;
			double phi = t * halfAngle;
			float axis = (float) (r * Math.cos(phi));
			float ringR = (float) (r * Math.sin(phi));
			float c = (float) Math.cos(theta);
			float s = (float) Math.sin(theta);
			float x = cx + nx * axis + (ux * c + vx * s) * ringR;
			float y = cy + ny * axis + (uy * c + vy * s) * ringR;
			float z = cz + nz * axis + (uz * c + vz * s) * ringR;
			int sx = projectX(proj, pt, x, y, z);
			int sy = projectY(proj, pt, x, y, z);
			if (sx != Integer.MIN_VALUE && prevX != Integer.MIN_VALUE) g.drawLine(prevX, prevY, sx, sy);
			prevX = sx;
			prevY = sy;
		}
	}

	private float[] getDisplayDirection(EffectorDefinition def) {
		float dx = def.forceDirectionX;
		float dy = def.forceDirectionY;
		float dz = def.forceDirectionZ;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 0.001f) {
			return new float[] { 0f, -1f, 0f };
		}
		dx /= len;
		dy /= len;
		dz /= len;
		if (def.invertDirection) {
			dx = -dx;
			dy = -dy;
			dz = -dz;
		}
		return new float[] { dx, dy, dz };
	}

	private float[] buildBasis(float nx, float ny, float nz) {
		float ax = Math.abs(nx) < 0.9f ? 1f : 0f;
		float ay = Math.abs(nx) < 0.9f ? 0f : 1f;
		float az = 0f;
		float ux = ay * nz - az * ny;
		float uy = az * nx - ax * nz;
		float uz = ax * ny - ay * nx;
		float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
		if (ul < 0.001f) {
			ux = 1f;
			uy = 0f;
			uz = 0f;
			ul = 1f;
		}
		ux /= ul;
		uy /= ul;
		uz /= ul;
		float vx = ny * uz - nz * uy;
		float vy = nz * ux - nx * uz;
		float vz = nx * uy - ny * ux;
		return new float[] { ux, uy, uz, vx, vy, vz };
	}

	private void drawLine3d(Graphics2D g, float[] proj, float[] pt, float x1, float y1, float z1, float x2, float y2, float z2) {
		int sx1 = projectX(proj, pt, x1, y1, z1);
		int sy1 = projectY(proj, pt, x1, y1, z1);
		int sx2 = projectX(proj, pt, x2, y2, z2);
		int sy2 = projectY(proj, pt, x2, y2, z2);
		if (sx1 != Integer.MIN_VALUE && sx2 != Integer.MIN_VALUE) g.drawLine(sx1, sy1, sx2, sy2);
	}

	private int projectX(float[] proj, float[] point, float x, float y, float z) {
		point[0] = x;
		point[1] = y;
		point[2] = z;
		point[3] = 1f;
		Mat4.projectVec(point, proj, point);
		if (point[3] <= 0) return Integer.MIN_VALUE;
		return round(point[0]);
	}

	private int projectY(float[] proj, float[] point, float x, float y, float z) {
		point[0] = x;
		point[1] = y;
		point[2] = z;
		point[3] = 1f;
		Mat4.projectVec(point, proj, point);
		if (point[3] <= 0) return Integer.MIN_VALUE;
		return round(point[1]);
	}
}
