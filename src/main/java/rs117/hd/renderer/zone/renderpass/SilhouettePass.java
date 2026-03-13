package rs117.hd.renderer.zone.renderpass;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.function.ToIntFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.config.NPCSilhouetteMode;
import rs117.hd.config.PlayerSilhouetteMode;
import rs117.hd.opengl.GLOcclusionQueries;
import rs117.hd.opengl.shader.BasicSceneProgram;
import rs117.hd.opengl.shader.DepthSceneShaderProgram;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.renderer.zone.DynamicModelVAO;
import rs117.hd.renderer.zone.DynamicModelVAO.DrawCall;
import rs117.hd.renderer.zone.ZoneRenderer;
import rs117.hd.utils.Camera;
import rs117.hd.utils.CommandBuffer;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.RenderState;

import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_EQUAL;
import static org.lwjgl.opengl.GL11C.GL_GEQUAL;
import static org.lwjgl.opengl.GL11C.GL_KEEP;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_REPLACE;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_ZERO;
import static org.lwjgl.opengl.GL13C.GL_MULTISAMPLE;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static rs117.hd.config.PlayerSilhouetteMode.Player_Follower;
import static rs117.hd.renderer.zone.ZoneRenderer.ACTOR_STENCIL_REF;
import static rs117.hd.renderer.zone.ZoneRenderer.CANOPY_STENCIL_REF;
import static rs117.hd.renderer.zone.ZoneRenderer.OPAQUE_STENCIL_REF;
import static rs117.hd.utils.MathUtils.*;

@Singleton
public final class SilhouettePass {
	@Inject
	private FrameTimer frameTimer;

	@Inject
	private ZoneRenderer renderer;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private Client client;

	private Player localPlayer;
	private Actor localPlayerInteracting, localPlayerFollower;
	private CommandBuffer cmd;
	private RenderState renderState;
	private DepthSceneShaderProgram depthSceneProgram;
	private BasicSceneProgram basicSceneProgram;

	private boolean configPlayerCanopy;
	private float configSilhouetteThreshold;
	private Color configPlayerSilhouetteColor;
	private Color configFriendlyNPCSilhouetteColor;
	private Color configHostileNPCSilhouetteColor;
	private PlayerSilhouetteMode configPlayerSilhouette;
	private NPCSilhouetteMode configNPCSilhouette;

	private final AlphaSortPredicate alphaSortPred = new AlphaSortPredicate();
	private final Comparator<SilhouetteDraw> alphaSortComparator = Comparator.comparingInt(alphaSortPred);

	private final SilhouetteDraw playerSilhouetteDraw = new SilhouetteDraw(true);
	private final ArrayDeque<SilhouetteDraw> freeDraws = new ArrayDeque<>();
	private final List<SilhouetteDraw> activeDraws = new ArrayList<>();
	private final List<SilhouetteDraw> pendingDraw = new ArrayList<>();
	private final HashMap<Actor, Actor> actorToInteracting = new HashMap<>();

	private static final class AlphaSortPredicate implements ToIntFunction<SilhouetteDraw> {
		int cx, cy, cz;

		@Override
		public int applyAsInt(SilhouetteDraw m) {
			return (m.x - cx) * (m.x - cx) + (m.y - cy) * (m.y - cy) + (m.z - cz) * (m.z - cz);
		}
	}

	public void initialize(RenderState renderState, DepthSceneShaderProgram depthSceneProgram, BasicSceneProgram basicSceneProgram) {
		this.renderState = renderState;
		this.depthSceneProgram = depthSceneProgram;
		this.basicSceneProgram = basicSceneProgram;

		cmd = new CommandBuffer("Silhouette", renderState);
		updateCachedConfigs();
	}

	public void updateCachedConfigs() {
		configPlayerCanopy = config.playerCanopy();
		configSilhouetteThreshold = 1.0f - saturate(config.silhouetteThreshold() / 100.0f);
		configPlayerSilhouette = config.playerSilhouette();
		configNPCSilhouette = config.npcSilhouette();
		configPlayerSilhouetteColor = config.playerSilhouetteColor();
		configFriendlyNPCSilhouetteColor = config.friendlyNpcSilhouetteColor();
		configHostileNPCSilhouetteColor = config.hostileNpcSilhouetteColor();
	}

	public void destroy() {
		for(SilhouetteDraw actorSilhouette : activeDraws)
			actorSilhouette.destroy();
		activeDraws.clear();

		cmd = null;
		renderState = null;
	}

	private void putInteracting(Actor actor) {
		Actor interacting = actor.getInteracting();
		if(interacting != null)
			actorToInteracting.put(actor, interacting);
	}

	private void buildInteractingMap() {
		WorldView root = client.getTopLevelWorldView();
		if(root == null)
			return;

		actorToInteracting.clear();

		root.players().forEach(this::putInteracting);
		root.npcs().forEach(this::putInteracting);
		for (WorldEntity we : root.worldEntities()) {
			WorldView wev = we.getWorldView();
			if(wev != null) {
				wev.players().forEach(this::putInteracting);
				wev.npcs().forEach(this::putInteracting);
			}
		}
	}

	public void draw() {
		if(configPlayerSilhouette == PlayerSilhouetteMode.Disabled &&
		   configNPCSilhouette == NPCSilhouetteMode.Disabled &&
		   !configPlayerCanopy)
			return;

		localPlayer = client.getLocalPlayer();
		localPlayerInteracting = localPlayer != null ? client.getLocalPlayer().getInteracting() : null;
		localPlayerFollower = localPlayer != null ? client.getFollower() : null;

		buildInteractingMap();

		frameTimer.begin(Timer.DRAW_SILHOUETTES);
		renderState.framebuffer.set(GL_DRAW_FRAMEBUFFER, plugin.fboScene);
		if (plugin.msaaSamples > 1) {
			renderState.enable.set(GL_MULTISAMPLE);
		} else {
			renderState.disable.set(GL_MULTISAMPLE);
		}
		renderState.viewport.set(0, 0, plugin.sceneResolution[0], plugin.sceneResolution[1]);
		renderState.ido.set(renderer.indirectDrawCmds.id);
		renderState.apply();

		final Camera camera = renderer.sceneCamera;
		alphaSortPred.cx = (int)camera.getPositionX();
		alphaSortPred.cy = (int)camera.getPositionY();
		alphaSortPred.cz = (int)camera.getPositionZ();
		pendingDraw.sort(alphaSortComparator);

		frameTimer.begin(Timer.RENDER_SILHOUETTES);
		long currentTime = System.currentTimeMillis();
		for(int i = 0; i < pendingDraw.size(); i++) {
			SilhouetteDraw silhouetteDraw = pendingDraw.get(i);
			if(!silhouetteDraw.drawCall.isValid())
				continue;

			if(!silhouetteDraw.initialized)
				silhouetteDraw.initialize();

			silhouetteDraw.draw();
			silhouetteDraw.drawCall.reset();
			silhouetteDraw.lastDrawTimeMS = currentTime;

			renderState.disable.set(GL_DEPTH_TEST);
			renderState.disable.set(GL_STENCIL_TEST);
			renderState.disable.set(GL_BLEND);
			renderState.depthMask.set(true);
			renderState.colorMask.set(true, true, true, true);
			renderState.stencilMask.set(0);
		}
		renderState.apply();
		pendingDraw.clear();

		for(int i = activeDraws.size() - 1; i >= 0; --i) {
			SilhouetteDraw silhouetteDraw = activeDraws.get(i);
			if((currentTime - silhouetteDraw.lastDrawTimeMS) > 10000) {
				freeDraws.add(silhouetteDraw);
				activeDraws.remove(i);
			}
		}

		frameTimer.end(Timer.RENDER_SILHOUETTES);
		frameTimer.end(Timer.DRAW_SILHOUETTES);
	}

	private SilhouetteDraw getSilhouetteDraw(GameObject gameObject, Actor actor) {
		if(actor == null)
			return null;

		if(actor == localPlayer)
			return playerSilhouetteDraw;

		final boolean isNpc = actor instanceof NPC;
		final int actorId = ModelHash.getIdOrIndex(gameObject.getHash());

		SilhouetteDraw silhouetteDraw;
		for(int i = 0; i < activeDraws.size(); i++) {
			silhouetteDraw = activeDraws.get(i);
			if(silhouetteDraw.actorId == actorId && silhouetteDraw.isNPC == isNpc)
				return silhouetteDraw;
		}

		silhouetteDraw = freeDraws.poll();
		if(silhouetteDraw == null)
			silhouetteDraw = new SilhouetteDraw(false);
		silhouetteDraw.actorId = actorId;
		silhouetteDraw.isNPC = isNpc;
		silhouetteDraw.isHostile = isNpc && isNPCHostile((NPC) actor);
		silhouetteDraw.silhouetteFadeAlpha = 0.0f;
		activeDraws.add(silhouetteDraw);
		return silhouetteDraw;
	}

	private boolean isNPCHostile(NPC npc) {
		if(localPlayer == null)
			return false;

		var npcComposition = npc.getComposition();
		if(npcComposition == null || npcComposition.isFollower() || (npcComposition.getCombatLevel() * 2) < localPlayer.getCombatLevel())
			return false;

		final String[] actions = npcComposition.getActions();
		if(actions == null || actions.length == 0)
			return false;

		for(int i = 0; i < actions.length; i++) {
			if(actions[i] != null && actions[i].equalsIgnoreCase("Attack"))
				return true;
		}

		return false;
	}

	public float getCanopyFadeStrength() {
		return playerSilhouetteDraw.canopyFadeStrength;
	}

	public boolean isSilhouetteEnabled(Actor actor) {
		if(actor instanceof Player) {
			switch (configPlayerSilhouette) {
				case Player:
				case Player_Follower:
					return actor == localPlayer;
				case Everyone:
					return true;
				case Disabled:
				default:
					return false;
			}
		} else if(actor instanceof NPC) {
			if(configPlayerSilhouette == Player_Follower) {
				if(actor == localPlayerFollower)
					return true;
				Actor interacting = actorToInteracting.get(actor);
				if(interacting == localPlayer)
					return true;
			}

			switch (configNPCSilhouette) {
				case Interacting:
					return actor == localPlayerInteracting;
				case Hostile:
					return isNPCHostile((NPC) actor);
				case All:
					return true;
				case Disabled:
				default:
					return false;
			}
		}

		return false;
	}

	public synchronized void addSilhouetteDraw(GameObject gameObject, Actor actor, DynamicModelVAO.View drawView, int x, int y, int z) {
		SilhouetteDraw silhouetteDraw = getSilhouetteDraw(gameObject, actor);
		if(silhouetteDraw == null)
			return;
		silhouetteDraw.x = x;
		silhouetteDraw.y = y;
		silhouetteDraw.z = z;
		drawView.draw(silhouetteDraw.drawCall);
		assert !pendingDraw.contains(silhouetteDraw) : "SilhouetteDraw already pending!";
		pendingDraw.add(silhouetteDraw);
	}

	final class SilhouetteDraw {
		@Getter
		private final DrawCall drawCall = new DrawCall();
		private final GLOcclusionQueries potentialQuery = new GLOcclusionQueries();
		private final GLOcclusionQueries opaqueQuery = new GLOcclusionQueries();
		private final GLOcclusionQueries canopyQuery;

		private int x, y, z;
		private int actorId;
		private long lastDrawTimeMS;
		private float canopyFadeStrength;
		private float silhouetteFadeAlpha;
		private boolean initialized = false;
		private boolean isNPC;
		private boolean isHostile;

		public SilhouetteDraw(boolean canopy) {
			this.canopyQuery = canopy ? new GLOcclusionQueries() : null;
		}

		public void initialize() {
			potentialQuery.initialize();
			opaqueQuery.initialize();
			if (canopyQuery != null)
				canopyQuery.initialize();
			initialized = true;
		}

		public void destroy() {
			potentialQuery.destroy();
			opaqueQuery.destroy();
			if (canopyQuery != null)
				canopyQuery.destroy();
		}

		public void draw() {
			renderState.disable.set(GL_DEPTH_TEST);
			renderState.enable.set(GL_CULL_FACE);
			renderState.depthFunc.set(GL_GEQUAL);
			renderState.depthMask.set(false);
			renderState.colorMask.set(false, false, false, false);
			renderState.stencilMask.set(0);
			renderState.apply();

			depthSceneProgram.use();

			// Resue Scene Command Buffer since it has already been drawn
			cmd.reset();
			cmd.BindVertexArray(drawCall.vao);
			cmd.DrawArrays(
				GL_TRIANGLES,
				drawCall.offset,
				drawCall.count
			);

			// Occlusion potentially with depth testing disabled
			potentialQuery.beginQuery(true);
			cmd.execute();
			potentialQuery.endQuery();

			final int potentiallyVisiblePixels = potentialQuery.getVisiblePixels(plugin.msaaSamples) / 2;
			if(potentiallyVisiblePixels > 0) {
				renderState.enable.set(GL_DEPTH_TEST);
				renderState.enable.set(GL_STENCIL_TEST);

				boolean drawSilhouette = isNPC ? configNPCSilhouette != NPCSilhouetteMode.Disabled : configPlayerSilhouette != PlayerSilhouetteMode.Disabled;
				if (drawSilhouette) {
					renderState.stencilMask.set(0xFF);
					renderState.stencilFunc.set(GL_NOTEQUAL, ACTOR_STENCIL_REF, ACTOR_STENCIL_REF);
					renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_REPLACE);
					renderState.depthMask.set(true);
					renderState.apply();

					// Write the Actor Stencil Reference for testing Occlusion Against
					cmd.execute();

					renderState.stencilMask.set(0);
					renderState.stencilFunc.set(GL_EQUAL, ACTOR_STENCIL_REF, ACTOR_STENCIL_REF);
					renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_KEEP);
					renderState.depthMask.set(false);
					renderState.apply();

					opaqueQuery.beginQuery(true);
					cmd.execute();
					opaqueQuery.endQuery();

					final float opaqueVisibleRatio = opaqueQuery.getVisibilityRatio(
						potentiallyVisiblePixels,
						plugin.msaaSamples
					);

					final float fadeTime = 1.0f / 0.1f;
					if(opaqueVisibleRatio < configSilhouetteThreshold) {
						silhouetteFadeAlpha = min(1.0f, silhouetteFadeAlpha + plugin.deltaTime * fadeTime);
					} else {
						silhouetteFadeAlpha = max(0.0f, silhouetteFadeAlpha - plugin.deltaTime * fadeTime);
					}

					Color silhouetteColor = isNPC ? isHostile ? configHostileNPCSilhouetteColor : configFriendlyNPCSilhouetteColor : configPlayerSilhouetteColor;
					float silhouetteAlpha = (silhouetteColor.getAlpha() / 255f) * silhouetteFadeAlpha * (1.0f - canopyFadeStrength);

					if(silhouetteFadeAlpha > 0.0f) {
						renderState.enable.set(GL_BLEND);
						renderState.depthFunc.set(GL_LEQUAL);
						renderState.blendFunc.set(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
						renderState.colorMask.set(true, true, true, true);

						renderState.stencilMask.set(OPAQUE_STENCIL_REF | CANOPY_STENCIL_REF | ACTOR_STENCIL_REF);
						renderState.stencilFunc.set(
							GL_EQUAL,
							OPAQUE_STENCIL_REF,
							OPAQUE_STENCIL_REF | CANOPY_STENCIL_REF | ACTOR_STENCIL_REF
						);
						renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_ZERO);
						renderState.apply();

						basicSceneProgram.use();
						basicSceneProgram.uniColor.set(silhouetteColor, silhouetteAlpha);

						cmd.execute();

						renderState.disable.set(GL_BLEND);
					}

					if (configPlayerCanopy && canopyQuery != null) {
						// Occlusion Test to see if we're behind canopy
						renderState.stencilMask.set(0);
						renderState.stencilFunc.set(GL_EQUAL, CANOPY_STENCIL_REF, CANOPY_STENCIL_REF);
						renderState.stencilOp.set(GL_KEEP, GL_KEEP, GL_KEEP);
						renderState.depthFunc.set(GL_LEQUAL);
						renderState.depthMask.set(false);
						renderState.colorMask.set(false, false, false, false);
						renderState.apply();

						depthSceneProgram.use();

						canopyQuery.beginQuery(true);
						cmd.execute();
						canopyQuery.endQuery();

						canopyFadeStrength = mix
							(
								canopyFadeStrength,
								canopyQuery.getVisibilityRatio(potentiallyVisiblePixels, plugin.msaaSamples),
								plugin.deltaTime * 4.0f
							);
					} else {
						canopyFadeStrength = 0.0f;
					}
				}
			}
		}
	}
}
