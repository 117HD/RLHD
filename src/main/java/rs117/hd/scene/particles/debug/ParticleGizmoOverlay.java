/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 */
package rs117.hd.scene.particles.debug;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.overlays.TileInfoOverlay;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.utils.Mat4;

import static rs117.hd.utils.MathUtils.cos;
import static rs117.hd.utils.MathUtils.round;
import static rs117.hd.utils.MathUtils.sin;

import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;

@Singleton
public class ParticleGizmoOverlay extends Overlay implements MouseListener
{
	private static final int INNER_HANDLE_RING = 19;
	private static final int OUTER_HANDLE_RING = 25;
	private static final int INNER_DOT = 6;
	private static final int GIZMO_HOVER_RADIUS = OUTER_HANDLE_RING / 2 + 5;
	private static final Color ACTIVE_COLOR = Color.WHITE;
	private static final Color INACTIVE_COLOR = Color.RED;
	private static final int EMITTER_DOT_R = 4;
	private static final int PARTICLE_DOT_R = 4;
	private static final Color EMITTER_DEBUG_COLOR = new Color(0, 255, 0, 200);
	private static final Color PARTICLE_DEBUG_COLOR = new Color(255, 105, 180, 240);
	private static final Color PARTICLE_DEBUG_OUTLINE = new Color(180, 50, 120, 255);
	private static final Color TRAJECTORY_LINE_COLOR = new Color(100, 200, 255, 160);
	private static final Color BOUNDS_SPREAD_COLOR = new Color(150, 220, 255, 180);
	private static final float BOUNDS_SPEED_SCALE = 3.125f;
	private static final Color PLACE_MODE_TILE_OUTLINE = new Color(100, 255, 150, 220);

	/** When non-null, show debug dots only for emitters/particles with this particle def id. */
	private String debugParticleId;
	private boolean overlayActive;

	/** Place mode: when true, hovering shows tile outline and click places emitter. */
	private boolean placeModeActive;
	private String placeModeParticleId;
	private boolean placeModeMouseRegistered;
	private Runnable onPlaceModeChanged;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ParticleManager particleManager;

	@Inject
	private EventBus eventBus;

	private boolean menuRegistered;

	public ParticleGizmoOverlay()
	{
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}
	
	public boolean isOverlayActive()
	{
		return overlayActive;
	}

	public boolean isDebugForParticleId(String pid)
	{
		return matchesParticleId(debugParticleId, pid);
	}

	public void toggleDebugForParticleId(String pid)
	{
		if (pid == null || pid.isEmpty())
			return;
		if (matchesParticleId(debugParticleId, pid))
			debugParticleId = null;
		else
			debugParticleId = pid;
	}

	public void setActive(boolean activate)
	{
		overlayActive = activate;
		if (activate)
		{
			overlayManager.add(this);
			if (!menuRegistered) {
				eventBus.register(this);
				menuRegistered = true;
			}
		}
		else
		{
			overlayManager.remove(this);
			debugParticleId = null;
			setPlaceMode(false, null);
			if (menuRegistered) {
				eventBus.unregister(this);
				menuRegistered = false;
			}
		}
	}

	/** Enable place mode: tile outline on hover, click to place selected particle and exit. */
	public void setPlaceMode(boolean active, String particleId)
	{
		boolean wasActive = placeModeActive;
		placeModeActive = active;
		placeModeParticleId = particleId;
		if (active)
		{
			if (!overlayActive)
				setActive(true);
			if (!placeModeMouseRegistered)
			{
				mouseManager.registerMouseListener(0, this);
				placeModeMouseRegistered = true;
			}
		}
		else
		{
			if (placeModeMouseRegistered)
			{
				mouseManager.unregisterMouseListener(this);
				placeModeMouseRegistered = false;
			}
			placeModeParticleId = null;
		}
		if (wasActive != placeModeActive && onPlaceModeChanged != null)
			onPlaceModeChanged.run();
	}

	public void setOnPlaceModeChanged(Runnable r)
	{
		onPlaceModeChanged = r;
	}

	public boolean isPlaceModeActive()
	{
		return placeModeActive;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!overlayActive)
			return;

		int type = event.getType();
		if (type != MenuAction.WALK.getId() && type != MenuAction.SET_HEADING.getId())
			return;

		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
			return;

		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null)
			return;

		float[] projectionMatrix = buildProjectionMatrix();
		int currentPlane = client.getTopLevelWorldView() != null
			? client.getTopLevelWorldView().getPlane()
			: 0;

		// Check all scene emitters - project each to screen and see if mouse is over gizmo.
		// This avoids tile-selection issues when emitters have large height offsets.
		List<ParticleEmitter> emittersUnderMouse = new java.util.ArrayList<>();
		float[] pos = new float[3];
		int[] planeOut = new int[1];
		float[] point = new float[4];
		for (ParticleEmitter em : particleManager.getSceneEmitters())
		{
			if (!particleManager.getEmitterSpawnPosition(ctx, em, pos, planeOut))
				continue;
			if (planeOut[0] != currentPlane)
				continue;
			point[0] = pos[0];
			point[1] = pos[1];
			point[2] = pos[2];
			point[3] = 1f;
			Mat4.projectVec(point, projectionMatrix, point);
			if (point[3] <= 0)
				continue;
			int sx = round(point[0]);
			int sy = round(point[1]);
			double dist = Math.hypot(mouse.getX() - sx, mouse.getY() - sy);
			if (dist <= GIZMO_HOVER_RADIUS)
				emittersUnderMouse.add(em);
		}

		if (emittersUnderMouse.isEmpty())
			return;

		MenuEntry parent = client.createMenuEntry(-1).setOption("Particle").setType(MenuAction.RUNELITE);
		Menu submenu = parent.createSubMenu();

		Set<String> addedDebugIds = new HashSet<>();
		for (ParticleEmitter em : emittersUnderMouse)
		{
			String pid = em.getParticleId();
			if (pid == null || pid.isEmpty())
				continue;

			if (addedDebugIds.add(pid))
			{
				boolean showing = isDebugForParticleId(pid);
				String option = showing ? "Hide debug" : "Show debug";
				String label = emittersUnderMouse.size() > 1 ? option + " (" + pid + ")" : option;
				String pidCapture = pid;
				submenu.createMenuEntry(-1)
					.setOption(label)
					.setTarget("Tile")
					.setType(MenuAction.RUNELITE)
					.onClick(e -> toggleDebugForParticleId(pidCapture));
			}
		}

		for (ParticleEmitter em : emittersUnderMouse)
		{
			String pid = em.getParticleId();
			if (pid == null)
				pid = "?";
			boolean active = em.isActive();
			String option = active ? "Hide particle" : "Show particle";
			String label = emittersUnderMouse.size() > 1 ? option + " (" + pid + ")" : option;
			ParticleEmitter emCapture = em;
			submenu.createMenuEntry(-1)
				.setOption(label)
				.setTarget("Tile")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> emCapture.active(!emCapture.isActive()));
		}

		Set<String> addedEditIds = new HashSet<>();
		for (ParticleEmitter em : emittersUnderMouse)
		{
			String pid = em.getParticleId();
			if (pid == null || pid.isEmpty() || !addedEditIds.add(pid))
				continue;
			String option = "Edit config";
			String label = emittersUnderMouse.size() > 1 ? option + " (" + pid + ")" : option;
			String pidCapture = pid;
			submenu.createMenuEntry(-1)
				.setOption(label)
				.setTarget("Tile")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> plugin.openParticleConfig(pidCapture));
		}

		List<ParticleEmitter> toRemove = new java.util.ArrayList<>(emittersUnderMouse);
		String removeLabel = toRemove.size() > 1 ? "Remove (" + toRemove.size() + ")" : "Remove";
		submenu.createMenuEntry(-1)
			.setOption(removeLabel)
			.setTarget("Tile")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> clientThread.invokeLater(() -> {
				for (ParticleEmitter em : toRemove)
					particleManager.removeEmitter(em);
			}));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null)
			return null;

		int currentPlane = client.getTopLevelWorldView() != null
			? client.getTopLevelWorldView().getPlane()
			: 0;

		// Place mode: draw tile outline under mouse
		if (placeModeActive)
		{
			Point mousePos = client.getMouseCanvasPosition();
			if (mousePos != null && mousePos.getX() >= 0 && mousePos.getY() >= 0)
			{
				Tile[][][] tiles = ctx.scene.getExtendedTiles();
				float mx = mousePos.getX();
				float my = mousePos.getY();
				tileSearch:
				for (int z = currentPlane; z >= 0; z--)
				{
					for (int x = 0; x < EXTENDED_SCENE_SIZE; x++)
					{
						for (int y = 0; y < EXTENDED_SCENE_SIZE; y++)
						{
							Tile tile = tiles[z][x][y];
							if (tile == null) continue;
							Polygon poly = tileInfoOverlay.getCanvasTilePoly(client, ctx, tile);
							if (poly != null && poly.contains(mx, my))
							{
								g.setColor(PLACE_MODE_TILE_OUTLINE);
								g.setStroke(new BasicStroke(2));
								g.drawPolygon(poly);
								break tileSearch;
							}
						}
					}
				}
			}
		}

		float[] projectionMatrix = buildProjectionMatrix();

		Stroke thinDashed = new BasicStroke(
			1,
			BasicStroke.CAP_BUTT,
			BasicStroke.JOIN_BEVEL,
			0,
			new float[]{3},
			0
		);
		Stroke thinLine = new BasicStroke(1);

		float[] pos = new float[3];
		int[] planeOut = new int[1];
		float[] point = new float[4];

		// When debug mode is on (toggled by clicking a gizmo), draw emitter and particle dots for that particle def only
		if (debugParticleId != null)
		{
			String pid = debugParticleId;
			g.setColor(EMITTER_DEBUG_COLOR);
			for (ParticleEmitter em : particleManager.getSceneEmitters())
			{
				if (!matchesParticleId(em.getParticleId(), pid))
					continue;
				if (!particleManager.getEmitterSpawnPosition(ctx, em, pos, planeOut))
					continue;
				if (planeOut[0] != currentPlane)
					continue;
				point[0] = pos[0];
				point[1] = pos[1];
				point[2] = pos[2];
				point[3] = 1f;
				Mat4.projectVec(point, projectionMatrix, point);
				if (point[3] <= 0) continue;
				int sx = round(point[0]);
				int sy = round(point[1]);
				g.fillOval(sx - EMITTER_DOT_R, sy - EMITTER_DOT_R, EMITTER_DOT_R * 2, EMITTER_DOT_R * 2);
			}
			ParticleBuffer buf = particleManager.getParticleBuffer();
			g.setColor(PARTICLE_DEBUG_COLOR);
			for (int i = 0; i < buf.count; i++)
			{
				ParticleEmitter em = buf.emitter[i];
				if (em == null || !matchesParticleId(em.getParticleId(), pid))
					continue;
				if (buf.plane[i] != currentPlane)
					continue;
				float px = buf.posX[i];
				float py = buf.posY[i];
				float pz = buf.posZ[i];
				point[0] = px;
				point[1] = py;
				point[2] = pz;
				point[3] = 1f;
				Mat4.projectVec(point, projectionMatrix, point);
				if (point[3] <= 0) continue;
				int sx = round(point[0]);
				int sy = round(point[1]);
				int d = PARTICLE_DOT_R * 2;
				g.setColor(PARTICLE_DEBUG_COLOR);
				g.fillOval(sx - PARTICLE_DOT_R, sy - PARTICLE_DOT_R, d, d);
				g.setColor(PARTICLE_DEBUG_OUTLINE);
				g.setStroke(new BasicStroke(2));
				g.drawOval(sx - PARTICLE_DOT_R, sy - PARTICLE_DOT_R, d, d);

				// Draw trajectory line from particle to predicted end point
				float[] endPos = predictEndPosition(buf, i);
				if (endPos != null) {
					float[] startScreen = new float[4];
					float[] endScreen = new float[4];
					startScreen[0] = px;
					startScreen[1] = py;
					startScreen[2] = pz;
					startScreen[3] = 1f;
					endScreen[0] = endPos[0];
					endScreen[1] = endPos[1];
					endScreen[2] = endPos[2];
					endScreen[3] = 1f;
					Mat4.projectVec(startScreen, projectionMatrix, startScreen);
					Mat4.projectVec(endScreen, projectionMatrix, endScreen);
					if (startScreen[3] > 0 && endScreen[3] > 0) {
						int x1 = round(startScreen[0]);
						int y1 = round(startScreen[1]);
						int x2 = round(endScreen[0]);
						int y2 = round(endScreen[1]);
						g.setColor(TRAJECTORY_LINE_COLOR);
						g.setStroke(thinLine);
						g.drawLine(x1, y1, x2, y2);
						int endDotR = 2;
						g.fillOval(x2 - endDotR, y2 - endDotR, endDotR * 2, endDotR * 2);
						g.setColor(PARTICLE_DEBUG_COLOR);
					}
				}
			}
		}

		for (ParticleEmitter em : particleManager.getSceneEmitters())
		{
			if (!particleManager.getEmitterSpawnPosition(ctx, em, pos, planeOut))
				continue;

			if (planeOut[0] != currentPlane)
				continue;

			point[0] = pos[0];
			point[1] = pos[1];
			point[2] = pos[2];
			point[3] = 1;

			Mat4.projectVec(point, projectionMatrix, point);

			if (point[3] <= 0)
				continue;

			int sx = round(point[0]);
			int sy = round(point[1]);

			Color ringColor = em.isActive() ? ACTIVE_COLOR : INACTIVE_COLOR;
			g.setColor(ringColor);

			g.setStroke(thinDashed);
			drawRing(g, sx, sy, INNER_HANDLE_RING);
			drawRing(g, sx, sy, OUTER_HANDLE_RING);

			g.setStroke(thinLine);
			drawCircleOutline(g, sx, sy, INNER_DOT);

			if (debugParticleId != null && matchesParticleId(em.getParticleId(), debugParticleId)) {
				drawEmitterBounds(g, em, pos, projectionMatrix, thinLine);
			}

			String name = em.getParticleId();
			if (name != null && !name.isEmpty())
			{
				g.setColor(Color.WHITE);
				g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));

				FontMetrics fm = g.getFontMetrics();
				int tw = fm.stringWidth(name);

				g.drawString(
					name,
					sx - tw / 2,
					sy + OUTER_HANDLE_RING / 2 + fm.getAscent() + 2
				);
			}
		}

		return null;
	}

	private float[] buildProjectionMatrix()
	{
		float[] m = Mat4.identity();
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
		Mat4.mul(m, Mat4.translate(vx, vy, 0));
		Mat4.mul(m, Mat4.scale(vw, vh, 1));
		Mat4.mul(m, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(m, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(m, plugin.viewProjMatrix);
		return m;
	}

	private static boolean matchesParticleId(String a, String b)
	{
		if (a == null || a.isEmpty()) return b == null || b.isEmpty();
		if (b == null || b.isEmpty()) return false;
		return a.equalsIgnoreCase(b);
	}

	/**
	 * Predicts where a particle will end up based on current velocity and remaining lifetime.
	 * Uses the same movement formula as MovingParticle.tick (ignoring falloff/speed transitions).
	 */
	private float[] predictEndPosition(ParticleBuffer buf, int i) {
		int remaining = buf.remainingTicks[i];
		if (remaining <= 0)
			return null;
		int vx = buf.velocityX[i];
		int vy = buf.velocityY[i];
		int vz = buf.velocityZ[i];
		int speedRef = buf.speedRef[i];
		if (speedRef <= 0)
			return null;
		long dxFixed = (long) vx * (long) (speedRef << 2) >> 23;
		long dyFixed = (long) vy * (long) (speedRef << 2) >> 23;
		long dzFixed = (long) vz * (long) (speedRef << 2) >> 23;
		float dx = (float) (dxFixed * remaining) / 4096f;
		float dy = (float) (dyFixed * remaining) / 4096f;
		float dz = (float) (dzFixed * remaining) / 4096f;
		return new float[] {
			buf.posX[i] + dx,
			buf.posY[i] + dy,
			buf.posZ[i] + dz
		};
	}

	private void drawEmitterBounds(Graphics2D g, ParticleEmitter em, float[] center, float[] proj, Stroke stroke) {
		float maxRadius = (em.getSpeedMax() / 16384f) * BOUNDS_SPEED_SCALE * em.getParticleLifeMax();
		if (maxRadius < 2f) maxRadius = 8f;

		float[] p = new float[4];

		g.setColor(BOUNDS_SPREAD_COLOR);
		g.setStroke(stroke);
		float syMin = em.getSpreadYawMin();
		float syMax = em.getSpreadYawMax();
		float spMin = em.getSpreadPitchMin();
		float spMax = em.getSpreadPitchMax();
		float baseYaw = em.getDirectionYaw();
		float basePitch = em.getDirectionPitch();
		float[] corners = {
			baseYaw + syMin, basePitch + spMin,
			baseYaw + syMin, basePitch + spMax,
			baseYaw + syMax, basePitch + spMin,
			baseYaw + syMax, basePitch + spMax
		};
		int[][] cornerScreens = new int[4][2];
		boolean[] cornerVisible = new boolean[4];
		for (int c = 0; c < 4; c++) {
			float yaw = corners[c * 2];
			float pitch = corners[c * 2 + 1];
			float cp = cos(pitch);
			float dirX = sin(yaw) * cp;
			float dirY = -sin(pitch);
			float dirZ = -cos(yaw) * cp;
			p[0] = center[0] + dirX * maxRadius;
			p[1] = center[1] + dirY * maxRadius;
			p[2] = center[2] + dirZ * maxRadius;
			p[3] = 1f;
			Mat4.projectVec(p, proj, p);
			if (p[3] > 0) {
				cornerScreens[c][0] = round(p[0]);
				cornerScreens[c][1] = round(p[1]);
				cornerVisible[c] = true;
			} else {
				cornerVisible[c] = false;
			}
		}
		int[] centerScreen = null;
		p[0] = center[0];
		p[1] = center[1];
		p[2] = center[2];
		p[3] = 1f;
		Mat4.projectVec(p, proj, p);
		if (p[3] > 0)
			centerScreen = new int[] { round(p[0]), round(p[1]) };
		if (centerScreen != null) {
			for (int c = 0; c < 4; c++) {
				if (cornerVisible[c])
					g.drawLine(centerScreen[0], centerScreen[1], cornerScreens[c][0], cornerScreens[c][1]);
			}
			for (int c = 0; c < 4; c++) {
				int next = (c + 1) % 4;
				if (cornerVisible[c] && cornerVisible[next])
					g.drawLine(cornerScreens[c][0], cornerScreens[c][1], cornerScreens[next][0], cornerScreens[next][1]);
			}
		}
	}

	private void drawRing(Graphics2D g, int centerX, int centerY, int diameter)
	{
		int d = (int) (Math.ceil(diameter / 2.0) * 2 - 1);
		int r = (int) Math.ceil(d / 2.0);
		g.drawOval(centerX - r, centerY - r, d, d);
	}

	private void drawCircleOutline(Graphics2D g, int centerX, int centerY, int diameter)
	{
		int r = (int) Math.ceil(diameter / 2.0);
		int s = diameter - 1;
		g.drawRoundRect(centerX - r, centerY - r, s, s, s - 1, s - 1);
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		if (!placeModeActive || placeModeParticleId == null || event.getButton() != MouseEvent.BUTTON1)
			return event;

		event.consume();

		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null)
			return event;

		Point mousePos = client.getMouseCanvasPosition();
		if (mousePos == null || mousePos.getX() < 0 || mousePos.getY() < 0)
			return event;

		int currentPlane = client.getTopLevelWorldView() != null
			? client.getTopLevelWorldView().getPlane()
			: 0;

		Tile[][][] tiles = ctx.scene.getExtendedTiles();
		float mx = mousePos.getX();
		float my = mousePos.getY();
		for (int z = currentPlane; z >= 0; z--)
		{
			for (int x = 0; x < EXTENDED_SCENE_SIZE; x++)
			{
				for (int y = 0; y < EXTENDED_SCENE_SIZE; y++)
				{
					Tile tile = tiles[z][x][y];
					if (tile == null) continue;
					Polygon poly = tileInfoOverlay.getCanvasTilePoly(client, ctx, tile);
					if (poly != null && poly.contains(mx, my))
					{
						int[] worldPos = ctx.extendedSceneToWorld(x, y, tile.getRenderLevel());
						WorldPoint wp = new WorldPoint(worldPos[0], worldPos[1], worldPos[2]);
						String pid = placeModeParticleId;
						clientThread.invokeLater(() -> particleManager.spawnEmitterFromDefinition(pid, wp));
						return event;
					}
				}
			}
		}
		return event;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (placeModeActive && event.getButton() == MouseEvent.BUTTON1)
			event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if (placeModeActive && event.getButton() == MouseEvent.BUTTON1)
			event.consume();
		return event;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent event) { return event; }

	@Override
	public MouseEvent mouseExited(MouseEvent event) { return event; }

	@Override
	public MouseEvent mouseMoved(MouseEvent event) { return event; }

	@Override
	public MouseEvent mouseDragged(MouseEvent event) { return event; }
}