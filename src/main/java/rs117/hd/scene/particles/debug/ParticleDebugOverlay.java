/*
 * Copyright (c) 2025, Mark7625.
 * Overlay: green = emitter positions, white = particle positions (helps see culling).
 */
package rs117.hd.scene.particles.debug;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.particles.ParticleManager;
import rs117.hd.scene.particles.core.buffer.ParticleBuffer;
import rs117.hd.utils.Mat4;

import static rs117.hd.utils.MathUtils.round;

@Singleton
public class ParticleDebugOverlay extends Overlay {

	private static final int EMITTER_DOT_R = 4;
	private static final int PARTICLE_DOT_R = 2;
	private static final Color EMITTER_COLOR = new Color(0, 255, 0, 200);
	private static final Color PARTICLE_COLOR = new Color(255, 255, 255, 220);

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ParticleManager particleManager;

	public ParticleDebugOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean active) {
		if (active) {
			overlayManager.add(this);
		} else {
			overlayManager.remove(this);
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		SceneContext ctx = plugin.getSceneContext();
		if (ctx == null || ctx.sceneBase == null)
			return null;

		int currentPlane = client.getTopLevelWorldView() != null ? client.getTopLevelWorldView().getPlane() : 0;
		float[] proj = Mat4.identity();
		int vw = client.getViewportWidth();
		int vh = client.getViewportHeight();
		Mat4.mul(proj, Mat4.translate(client.getViewportXOffset(), client.getViewportYOffset(), 0));
		Mat4.mul(proj, Mat4.scale(vw, vh, 1));
		Mat4.mul(proj, Mat4.translate(.5f, .5f, .5f));
		Mat4.mul(proj, Mat4.scale(.5f, -.5f, .5f));
		Mat4.mul(proj, plugin.viewProjMatrix);

		float[] pos = new float[3];
		int[] planeOut = new int[1];
		float[] point = new float[4];

		g.setColor(EMITTER_COLOR);
		for (var em : particleManager.getSceneEmitters()) {
			if (!particleManager.getEmitterSpawnPosition(ctx, em, pos, planeOut))
				continue;
			if (planeOut[0] != currentPlane)
				continue;
			point[0] = pos[0];
			point[1] = pos[1];
			point[2] = pos[2];
			point[3] = 1f;
			Mat4.projectVec(point, proj, point);
			if (point[3] <= 0) continue;
			int sx = round(point[0]);
			int sy = round(point[1]);
			g.fillOval(sx - EMITTER_DOT_R, sy - EMITTER_DOT_R, EMITTER_DOT_R * 2, EMITTER_DOT_R * 2);
		}

		ParticleBuffer buf = particleManager.getParticleBuffer();
		int[] cameraShift = plugin.cameraShift;
		g.setColor(PARTICLE_COLOR);
		for (int i = 0; i < buf.count; i++) {
			if (buf.plane[i] != currentPlane)
				continue;
			point[0] = buf.posX[i] + cameraShift[0];
			point[1] = buf.posY[i];
			point[2] = buf.posZ[i] + cameraShift[1];
			point[3] = 1f;
			Mat4.projectVec(point, proj, point);
			if (point[3] <= 0) continue;
			int sx = round(point[0]);
			int sy = round(point[1]);
			g.fillOval(sx - PARTICLE_DOT_R, sy - PARTICLE_DOT_R, PARTICLE_DOT_R * 2, PARTICLE_DOT_R * 2);
		}

		return null;
	}
}
