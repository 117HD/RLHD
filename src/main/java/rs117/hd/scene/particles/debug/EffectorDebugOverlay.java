package rs117.hd.scene.particles.debug;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import javax.annotation.Nullable;
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
import rs117.hd.scene.particles.effector.EffectorEffectSpec;
import rs117.hd.scene.particles.effector.EffectorPlacement;
import rs117.hd.utils.Mat4;

import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;
import static rs117.hd.utils.MathUtils.round;

@Singleton
public class EffectorDebugOverlay extends Overlay {
	private static final Color DOME_FILL_COLOR = new Color(80, 255, 255, 60);
	private static final Color DOME_RING_COLOR = new Color(90, 245, 255, 255);
	private static final Color DOME_MERIDIAN_COLOR = new Color(70, 220, 245, 255);
	private static final Color CENTER_LINE_COLOR = new Color(0, 220, 255, 200);
	private static final Color ARROW_COLOR = new Color(0, 255, 160, 230);
	private static final Color WHIRLPOOL_COLOR = new Color(120, 180, 255, 230);
	private static final Color WIND_ZONE_FILL = new Color(80, 255, 255, 75);
	private static final Color WIND_ZONE_LINE = new Color(80, 255, 255, 230);
	private static final Color WIND_SPREAD_LINE = new Color(255, 220, 60, 240);
	private static final Color WIND_LABEL_BG = new Color(0, 0, 0, 170);
	private static final Color WIND_LABEL_TEXT = new Color(230, 255, 245, 255);

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
		for (EffectorPlacement placement : effectorDefinitionManager.getAllPlacements()) {
			if (placement.getPlane() != currentPlane) continue;

			EffectorDefinition def = effectorDefinitionManager.getDefinition(placement.getEffectorId());
			if (def == null || def.effects.isEmpty()) continue;

			WorldPoint wp = new WorldPoint(placement.getWorldX(), placement.getWorldY(), placement.getPlane());
			int[] loc = ctx.worldToLocalFirst(wp, local);
			if (loc == null) continue;

			float centerLocalX = loc[0] + LOCAL_TILE_SIZE / 2f;
			float centerLocalZ = loc[1] + LOCAL_TILE_SIZE / 2f;
			float baseX = centerLocalX;
			float baseZ = centerLocalZ;
			float baseY = ParticleManager.getTerrainHeight(ctx, (int) centerLocalX, (int) centerLocalZ, loc[2]) - def.heightOffset;
			float radius = computeDebugRadius(def);
			EffectorEffectSpec whirlpool = findWhirlpoolEffect(def);
			EffectorEffectSpec wind = findWindEffect(def);
			boolean radialZone = def.radiusTiles > 0f && hasRadialEffect(def) && whirlpool == null;
			boolean whirlpoolZone = def.radiusTiles > 0f && whirlpool != null;
			boolean windZone = wind != null;

			if (whirlpoolZone) {
				drawSphereWire(g, proj, pt, baseX, baseY, baseZ, radius);
				drawWhirlpoolHelix(g, proj, pt, baseX, baseY, baseZ, radius, whirlpool.clockwise, whirlpool.inverted);
			} else if (radialZone) {
				EffectorEffectSpec radial = findRadialEffect(def);
				drawSphereWire(g, proj, pt, baseX, baseY, baseZ, radius);
				drawRadialSpokes(g, proj, pt, baseX, baseY, baseZ, radius, radial != null && radial.type == EffectorEffectSpec.EffectType.REPEL);
			} else if (windZone) {
				drawWindDebug(g, proj, pt, baseX, baseY, baseZ, radius, def, wind);
			}
		}
		return null;
	}

	private float computeDebugRadius(EffectorDefinition def) {
		float r;
		if (def.radiusTiles > 0f) {
			r = def.radiusTiles * LOCAL_TILE_SIZE;
		} else {
			EffectorEffectSpec wind = findWindEffect(def);
			if (wind != null) {
				r = def.getDebugRadiusLocal();
			} else if (def.isUnlimitedRange()) {
				EffectorEffectSpec primary = getPrimaryEffect(def);
				int strength = primary != null ? Math.abs(primary.signedMagnitude) : 0;
				r = strength * 0.015f;
			} else {
				r = def.getDebugRadiusLocal();
			}
		}
		return Math.max(48f, Math.min(2400f, r));
	}

	@Nullable
	private EffectorEffectSpec findWindEffect(EffectorDefinition def) {
		for (EffectorEffectSpec effect : def.effects) {
			if (effect.type == EffectorEffectSpec.EffectType.WIND) {
				return effect;
			}
		}
		return null;
	}

	private float[] getWindDirection(EffectorEffectSpec wind) {
		if (wind == null || !wind.hasWindDirection) {
			return new float[] { 0f, 1f, 0f };
		}
		return new float[] { wind.windDirX, wind.windDirY, wind.windDirZ };
	}

	private float getWindSpreadGroundRadius(float zoneRadius, EffectorEffectSpec wind) {
		float scale = 1.08f + 0.92f * wind.directionVarianceScale + 0.18f * wind.turbulenceScale;
		return zoneRadius * scale;
	}

	private void drawWindDebug(
		Graphics2D g,
		float[] proj,
		float[] pt,
		float cx,
		float cy,
		float cz,
		float radius,
		EffectorDefinition def,
		EffectorEffectSpec wind
	) {
		float[] dir = getWindDirection(wind);
		float dx = dir[0], dy = dir[1], dz = dir[2];
		float groundY = cy + def.heightOffset;
		float spreadRadius = getWindSpreadGroundRadius(radius, wind);

		drawSphereWire(g, proj, pt, cx, cy, cz, radius);
		drawWindGroundCircles(g, proj, pt, cx, groundY, cz, radius, spreadRadius);
		drawWindOutflowArrow(g, proj, pt, cx, cy, cz, radius, dx, dy, dz);
		drawWindLabel(g, proj, pt, cx, cy, cz, def, wind, spreadRadius);
	}

	private void drawWindGroundCircles(
		Graphics2D g,
		float[] proj,
		float[] pt,
		float cx,
		float groundY,
		float cz,
		float zoneRadius,
		float spreadRadius
	) {
		g.setColor(WIND_ZONE_FILL);
		fillRingOriented(g, proj, pt, cx, groundY, cz, zoneRadius, 36, 1f, 0f, 0f, 0f, 0f, 1f);

		Stroke prev = g.getStroke();
		g.setColor(WIND_ZONE_LINE);
		g.setStroke(new BasicStroke(2.5f));
		drawRingOriented(g, proj, pt, cx, groundY, cz, zoneRadius, 36, 1f, 0f, 0f, 0f, 0f, 1f);

		if (spreadRadius > zoneRadius * 1.02f) {
			g.setColor(WIND_SPREAD_LINE);
			g.setStroke(new BasicStroke(3f));
			drawRingOriented(g, proj, pt, cx, groundY, cz, spreadRadius, 36, 1f, 0f, 0f, 0f, 0f, 1f);
		}
		g.setStroke(prev);
	}

	private void drawWindOutflowArrow(
		Graphics2D g,
		float[] proj,
		float[] pt,
		float cx,
		float cy,
		float cz,
		float zoneRadius,
		float dx,
		float dy,
		float dz
	) {
		float tipDist = zoneRadius + Math.max(64f, zoneRadius * 0.4f);

		g.setColor(ARROW_COLOR);
		g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		drawArrow3d(g, proj, pt, cx, cy, cz, cx + dx * tipDist, cy + dy * tipDist, cz + dz * tipDist);
	}

	private void drawWindLabel(
		Graphics2D g,
		float[] proj,
		float[] pt,
		float cx,
		float cy,
		float cz,
		EffectorDefinition def,
		EffectorEffectSpec wind,
		float spreadRadius
	) {
		int sx = projectX(proj, pt, cx, cy, cz);
		int sy = projectY(proj, pt, cx, cy, cz);
		if (sx == Integer.MIN_VALUE) {
			return;
		}

		String id = def.id != null ? def.id : "wind";
		String zone = def.radiusTiles > 0f
			? String.format("%.0f-tile sphere", def.radiusTiles)
			: "unlimited range";
		String spread = String.format(
			"spread ring %.1f tiles (variance %.0f)",
			spreadRadius / LOCAL_TILE_SIZE,
			wind.directionVariance
		);
		String flow = String.format("steer ->  speed %.2f  intensity %.2f", wind.speed, wind.intensity);
		String tuning = String.format(
			"turbulence %.0f  effect %.0f%%",
			wind.turbulence,
			wind.effectPercent
		);
		String falloff = wind.edgeFalloff
			? String.format("edge falloff (pow %.1f) — stronger at center", wind.falloffPower > 0f ? wind.falloffPower : 1f)
			: "uniform strength in zone";

		String[] lines = {
			id,
			zone,
			"cyan sphere + inner disk = zone size",
			"yellow outer ring = direction spread",
			"green arrow = particle direction",
			spread,
			flow,
			tuning,
			falloff
		};

		Font prevFont = g.getFont();
		Font labelFont = prevFont.deriveFont(Font.BOLD, Math.max(11f, prevFont.getSize2D()));
		g.setFont(labelFont);
		int padX = 6;
		int padY = 4;
		int lineH = g.getFontMetrics().getHeight();
		int maxW = 0;
		for (String line : lines) {
			maxW = Math.max(maxW, g.getFontMetrics().stringWidth(line));
		}
		int boxW = maxW + padX * 2;
		int boxH = lineH * lines.length + padY * 2;
		int boxX = sx + 12;
		int boxY = sy - boxH - 8;

		g.setColor(WIND_LABEL_BG);
		g.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);
		g.setColor(WIND_LABEL_TEXT);
		int ty = boxY + padY + g.getFontMetrics().getAscent();
		for (String line : lines) {
			g.drawString(line, boxX + padX, ty);
			ty += lineH;
		}
		g.setFont(prevFont);
	}

	private void drawArrow3d(Graphics2D g, float[] proj, float[] pt, float x1, float y1, float z1, float x2, float y2, float z2) {
		float dx = x2 - x1;
		float dy = y2 - y1;
		float dz = z2 - z1;
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-4f) {
			return;
		}
		dx /= len;
		dy /= len;
		dz /= len;
		drawLine3d(g, proj, pt, x1, y1, z1, x2, y2, z2);

		float head = Math.min(18f, len * 0.35f);
		float hx = x2 - dx * head;
		float hy = y2 - dy * head;
		float hz = z2 - dz * head;
		float[] basis = buildBasis(dx, dy, dz);
		float ux = basis[0], uy = basis[1], uz = basis[2];
		float wing = head * 0.45f;
		drawLine3d(g, proj, pt, x2, y2, z2, hx + ux * wing, hy + uy * wing, hz + uz * wing);
		drawLine3d(g, proj, pt, x2, y2, z2, hx - ux * wing, hy - uy * wing, hz - uz * wing);
	}

	@Nullable
	private EffectorEffectSpec getPrimaryEffect(EffectorDefinition def) {
		if (def.effects.isEmpty()) {
			return null;
		}
		return def.effects.get(0);
	}

	@Nullable
	private EffectorEffectSpec findWhirlpoolEffect(EffectorDefinition def) {
		for (EffectorEffectSpec effect : def.effects) {
			if (effect.whirlpool) {
				return effect;
			}
		}
		return null;
	}

	private boolean hasRadialEffect(EffectorDefinition def) {
		return findRadialEffect(def) != null;
	}

	@Nullable
	private EffectorEffectSpec findRadialEffect(EffectorDefinition def) {
		for (EffectorEffectSpec effect : def.effects) {
			if (effect.radial) {
				return effect;
			}
		}
		return null;
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

	private void drawWhirlpoolHelix(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float radius, boolean clockwise, boolean inverted) {
		g.setColor(WHIRLPOOL_COLOR);
		g.setStroke(new BasicStroke(2f));
		final int coils = 3;
		final int segments = 28;
		double spinSign = clockwise ? -1.0 : 1.0;

		float skyY = cy - radius * 0.85f;
		float groundY = cy + radius * 0.85f;

		for (int coil = 0; coil < coils; coil++) {
			double phase = coil * (Math.PI * 2.0 / coils);
			int prevX = Integer.MIN_VALUE;
			int prevY = Integer.MIN_VALUE;
			for (int s = 0; s <= segments; s++) {
				double travelT = s / (double) segments;
				double geomT = inverted ? (1.0 - travelT) : travelT;
				float y = (float) (skyY + (groundY - skyY) * geomT);
				double angle = phase + geomT * Math.PI * 3.0 * spinSign;
				float ringR = (float) (radius * 0.65f * (1.0 - geomT * 0.35));
				float x = cx + (float) Math.cos(angle) * ringR;
				float z = cz + (float) Math.sin(angle) * ringR;
				int sx = projectX(proj, pt, x, y, z);
				int sy = projectY(proj, pt, x, y, z);
				if (sx != Integer.MIN_VALUE && prevX != Integer.MIN_VALUE) {
					g.drawLine(prevX, prevY, sx, sy);
				}
				prevX = sx;
				prevY = sy;
			}
		}

		g.setColor(CENTER_LINE_COLOR);
		drawLine3d(g, proj, pt, cx, skyY, cz, cx, groundY, cz);
	}

	private void drawSphereWire(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float radius) {
		final int latSegments = 8;
		final int lonSegments = 16;
		float[] basis = buildBasis(0f, 1f, 0f);
		float ux = basis[0], uy = basis[1], uz = basis[2];
		float vx = basis[3], vy = basis[4], vz = basis[5];
		float halfAngle = (float) Math.PI;

		g.setColor(DOME_FILL_COLOR);
		fillHemisphereSurfaceOriented(g, proj, pt, cx, cy, cz, radius, halfAngle, latSegments, lonSegments,
			0f, 1f, 0f, ux, uy, uz, vx, vy, vz);

		g.setColor(DOME_RING_COLOR);
		for (int i = 0; i <= latSegments; i++) {
			double t = i / (double) latSegments;
			double phi = t * halfAngle;
			float axis = (float) (radius * Math.cos(phi));
			float ringR = (float) (radius * Math.sin(phi));
			float rcy = cy + axis;
			drawRingOriented(g, proj, pt, cx, rcy, cz, ringR, lonSegments, ux, uy, uz, vx, vy, vz);
		}

		g.setColor(DOME_MERIDIAN_COLOR);
		for (int j = 0; j < lonSegments; j += 2) {
			double theta = j * (Math.PI * 2.0 / lonSegments);
			drawMeridianOriented(g, proj, pt, cx, cy, cz, radius, halfAngle, theta, 0f, 1f, 0f, ux, uy, uz, vx, vy, vz);
		}

		g.setColor(CENTER_LINE_COLOR);
		drawRingOriented(g, proj, pt, cx, cy, cz, 6f, 8, 1f, 0f, 0f, 0f, 0f, 1f);
	}

	private void drawRadialSpokes(Graphics2D g, float[] proj, float[] pt, float cx, float cy, float cz, float radius, boolean outward) {
		g.setColor(ARROW_COLOR);
		g.setStroke(new BasicStroke(2f));
		final int spokes = 8;
		float inner = radius * 0.12f;
		float outer = radius * 0.72f;

		for (int i = 0; i < spokes; i++) {
			double a = i * (Math.PI * 2.0 / spokes);
			float ox = (float) Math.cos(a);
			float oz = (float) Math.sin(a);
			float x1 = cx + ox * (outward ? inner : outer);
			float z1 = cz + oz * (outward ? inner : outer);
			float x2 = cx + ox * (outward ? outer : inner);
			float z2 = cz + oz * (outward ? outer : inner);
			drawLine3d(g, proj, pt, x1, cy, z1, x2, cy, z2);
			drawSpokeHead(g, proj, pt, x2, cy, z2, ox, 0f, oz, outward);
		}

		for (int sign : new int[] { 1, -1 }) {
			float y1 = cy + sign * (outward ? inner : outer);
			float y2 = cy + sign * (outward ? outer : inner);
			drawLine3d(g, proj, pt, cx, y1, cz, cx, y2, cz);
			drawSpokeHead(g, proj, pt, cx, y2, cz, 0f, sign, 0f, outward);
		}
	}

	private void drawSpokeHead(Graphics2D g, float[] proj, float[] pt, float tipX, float tipY, float tipZ,
		float dx, float dy, float dz, boolean outward) {
		float dirX = outward ? dx : -dx;
		float dirY = outward ? dy : -dy;
		float dirZ = outward ? dz : -dz;
		float hx = tipX - dirX * 16f;
		float hy = tipY - dirY * 16f;
		float hz = tipZ - dirZ * 16f;
		float px = -dirZ;
		float py = 0f;
		float pz = dirX;
		float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
		if (pl < 0.001f) {
			px = 1f;
			pz = 0f;
			pl = 1f;
		}
		px /= pl;
		pz /= pl;
		drawLine3d(g, proj, pt, tipX, tipY, tipZ, hx + px * 6f, hy, hz + pz * 6f);
		drawLine3d(g, proj, pt, tipX, tipY, tipZ, hx - px * 6f, hy, hz - pz * 6f);
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
