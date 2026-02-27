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
import java.awt.Stroke;
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
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.scene.particles.emitter.ParticleEmitter;
import rs117.hd.utils.Mat4;

import static rs117.hd.utils.MathUtils.round;

@Singleton
public class ParticleGizmoOverlay extends Overlay
{
	private static final int INNER_HANDLE_RING = 19;
	private static final int OUTER_HANDLE_RING = 25;
	private static final int INNER_DOT = 6;
	private static final int GIZMO_HOVER_RADIUS = OUTER_HANDLE_RING / 2 + 5;
	private static final Color ACTIVE_COLOR = Color.WHITE;
	private static final Color INACTIVE_COLOR = Color.RED;
	private static final int EMITTER_DOT_R = 4;
	private static final int PARTICLE_DOT_R = 2;
	private static final Color EMITTER_DEBUG_COLOR = new Color(0, 255, 0, 200);
	private static final Color PARTICLE_DEBUG_COLOR = new Color(255, 255, 255, 220);

	/** When non-null, show debug dots only for emitters/particles with this particle def id. */
	private String debugParticleId;
	private boolean overlayActive;

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
			if (menuRegistered) {
				eventBus.unregister(this);
				menuRegistered = false;
			}
		}
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

		MenuEntry parent = client.createMenuEntry(-1)
			.setOption("Particles")
			.setTarget("Tile")
			.setType(MenuAction.RUNELITE);
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
				point[0] = buf.posX[i];
				point[1] = buf.posY[i];
				point[2] = buf.posZ[i];
				point[3] = 1f;
				Mat4.projectVec(point, projectionMatrix, point);
				if (point[3] <= 0) continue;
				int sx = round(point[0]);
				int sy = round(point[1]);
				g.fillOval(sx - PARTICLE_DOT_R, sy - PARTICLE_DOT_R, PARTICLE_DOT_R * 2, PARTICLE_DOT_R * 2);
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
}