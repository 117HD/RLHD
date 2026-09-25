package rs117.hd.utils;

import java.awt.Color;
import rs117.hd.renderer.zone.passes.DebugDrawPass;
import rs117.hd.renderer.zone.passes.DebugDrawPass.Draw;
import rs117.hd.renderer.zone.passes.DebugDrawPass.PrimitiveDrawType;

public class DebugDraw {
	public static DebugDrawPass INSTANCE;

	public static void drawText(
		float x, float y, float z, String text, float scale,
		Color color, float duration
	) {
		DebugDrawPass inst = INSTANCE;
		if (inst == null || color == null || text == null)
			return;

		final Draw d = inst.pushDraw(PrimitiveDrawType.TEXT);
		d.x1 = x;
		d.y1 = y;
		d.z1 = z;
		d.x2 = scale;
		d.duration = duration;
		d.text = text;
		d.rgb = color.getRGB();
	}

	public static void drawText(float x, float y, float z, String text, float scale, Color color) {
		drawText(x, y, z, text, scale, color, -1);
	}

	public static void drawAABB(
		float cx, float cy, float cz, float hx, float hy, float hz,
		Color color, float duration, boolean filled
	) {
		DebugDrawPass inst = INSTANCE;
		if (inst == null || color == null)
			return;

		final Draw d = inst.pushDraw(PrimitiveDrawType.AABB);
		d.x1 = cx;
		d.y1 = cy;
		d.z1 = cz;
		d.x2 = hx;
		d.y2 = hy;
		d.z2 = hz;
		d.rgb = color.getRGB();
		d.duration = duration;
		d.filled = filled;
	}

	public static void drawAABB(
		float cx, float cy, float cz, float hx, float hy, float hz,
		Color color, boolean filled
	) {
		drawAABB(cx, cy, cz, hx, hy, hz, color, -1, filled);
	}

	public static void drawMinMax(
		float minX, float minY, float minZ,
		float maxX, float maxY, float maxZ,
		Color color, float duration, boolean filled
	) {
		DebugDrawPass inst = INSTANCE;
		if (inst == null || color == null)
			return;

		final Draw d = inst.pushDraw(PrimitiveDrawType.AABB);
		d.x1 = (minX + maxX) / 2f;
		d.y1 = (minY + maxY) / 2f;
		d.z1 = (minZ + maxZ) / 2f;
		d.x2 = (maxX - minX) / 2f;
		d.y2 = (maxY - minY) / 2f;
		d.z2 = (maxZ - minZ) / 2f;
		d.rgb = color.getRGB();
		d.duration = duration;
		d.filled = filled;
	}

	public static void drawMinMax(
		float minX, float minY, float minZ,
		float maxX, float maxY, float maxZ,
		Color color, boolean filled
	) {
		drawMinMax(minX, minY, minZ, maxX, maxY, maxZ, color, -1, filled);
	}

	public static void drawSphere(
		float cx, float cy, float cz, float radius,
		Color color, float duration, boolean filled
	) {
		DebugDrawPass inst = INSTANCE;
		if (inst == null || color == null)
			return;

		final Draw d = inst.pushDraw(PrimitiveDrawType.SPHERE);
		d.x1 = cx;
		d.y1 = cy;
		d.z1 = cz;
		d.x2 = radius;
		d.rgb = color.getRGB();
		d.duration = duration;
		d.filled = filled;
	}

	public static void drawSphere(
		float cx, float cy, float cz, float radius,
		Color color, boolean filled
	) {
		drawSphere(cx, cy, cz, radius, color, -1, filled);
	}

	public static void drawLine(
		float x1, float y1, float z1, float x2, float y2, float z2,
		Color color, float duration
	) {
		drawLine(x1, y1, z1, x2, y2, z2, 1f, color, duration);
	}

	public static void drawLine(
		float x1, float y1, float z1, float x2, float y2, float z2,
		float thickness, Color color
	) {
		drawLine(x1, y1, z1, x2, y2, z2, thickness, color, -1);
	}

	public static void drawLine(
		float x1, float y1, float z1, float x2, float y2, float z2,
		float thickness, Color color, float duration
	) {
		DebugDrawPass inst = INSTANCE;
		if (inst == null || color == null) return;

		final Draw d = inst.pushDraw(PrimitiveDrawType.LINE);
		d.x1 = x1;
		d.y1 = y1;
		d.z1 = z1;
		d.x2 = x2;
		d.y2 = y2;
		d.z2 = z2;
		d.thickness = thickness;
		d.rgb = color.getRGB();
		d.duration = duration;
		d.filled = false;
	}

	public static void drawArrow(
		float x1, float y1, float z1,
		float dx, float dy, float dz,
		float headLength, float thickness,
		Color color, float duration
	) {
		DebugDrawPass inst = INSTANCE;
		if (inst == null || color == null)
			return;

		float x2 = x1 + dx;
		float y2 = y1 + dy;
		float z2 = z1 + dz;

		drawLine(x1, y1, z1, x2, y2, z2, thickness, color, duration);

		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-5f)
			return;

		float invLen = 1f / len;
		float nx = dx * invLen;
		float ny = dy * invLen;
		float nz = dz * invLen;

		// Pick an axis that's not parallel to the direction.
		float ax = Math.abs(ny) < 0.999f ? 0 : 1;
		float ay = Math.abs(ny) < 0.999f ? 1 : 0;
		float az = 0;

		// right = normalize(dir × axis)
		float rx = ny * az - nz * ay;
		float ry = nz * ax - nx * az;
		float rz = nx * ay - ny * ax;

		float invRLen = 1f / (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
		rx *= invRLen;
		ry *= invRLen;
		rz *= invRLen;

		// up = dir × right
		float ux = ny * rz - nz * ry;
		float uy = nz * rx - nx * rz;
		float uz = nx * ry - ny * rx;

		float bx = x2 - nx * headLength;
		float by = y2 - ny * headLength;
		float bz = z2 - nz * headLength;
		float hs = headLength * 0.5f;

		addHead(bx, by, bz, rx, ry, rz, hs, x2, y2, z2, thickness, color, duration);
		addHead(bx, by, bz, ux, uy, uz, hs, x2, y2, z2, thickness, color, duration);
	}

	private static void addHead(
		float bx, float by, float bz,
		float vx, float vy, float vz,
		float hs,
		float x2, float y2, float z2,
		float thickness,
		Color color,
		float duration
	) {
		drawLine(bx + vx * hs, by + vy * hs, bz + vz * hs, x2, y2, z2, thickness, color, duration);
		drawLine(bx - vx * hs, by - vy * hs, bz - vz * hs, x2, y2, z2, thickness, color, duration);
	}

	public static void drawArrow(
		float x1, float y1, float z1, float dx, float dy, float dz,
		float headLength, float thickness, Color color
	) {
		drawArrow(x1, y1, z1, dx, dy, dz, headLength, thickness, color, -1);
	}
}
