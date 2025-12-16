/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.RenderCallbackManager;
import rs117.hd.HdPlugin;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.InheritTileColorType;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.tile_overrides.TileOverride.NONE;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.HDUtils.UNDERWATER_HSL;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
public class SceneUploader {
	private static final int MAX_VERTEX_COUNT = 6500;
	private static final int[] UP_NORMAL = { 0, -1, 0 };

	private static final float[] IDENTITY_UV = {
		0, 0, 0, 0,
		1, 0, 0, 0,
		0, 1, 0, 0
	};

	private static final int[] MAX_BRIGHTNESS_LOOKUP_TABLE = new int[8];
	private static final float[] LIGHT_DIR_MODEL = new float[] { 0.57735026f, 0.57735026f, 0.57735026f };
	// subtracts the X lowest lightness levels from the formula.
	// helps keep darker colors appropriately dark
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	// multiplier applied to vertex' lightness value.
	// results in greater lightening of lighter colors
	private static final float LIGHTNESS_MULTIPLIER = 3;
	// the minimum amount by which each color will be lightened
	private static final int BASE_LIGHTEN = 10;

	static {
		for (int i = 0; i < 8; i++)
			MAX_BRIGHTNESS_LOOKUP_TABLE[i] = (int) (127 - 72 * Math.pow(i / 7f, .05));
	}

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	public GamevalManager gamevalManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	public ProceduralGenerator proceduralGenerator;

	private int basex, basez, rid, level;

	private final Set<Integer> roofIds = new HashSet<>();
	private Scene currentScene;
	private Tile[][][] tiles;
	private byte[][][] settings;
	private int[][][] roofs;
	private short[][][] overlayIds;
	private short[][][] underlayIds;
	private int[][][] tileHeights;

	private final int[] worldPos = new int[3];
	private final int[][] vertices = new int[4][3];
	private final int[] vertexKeys = new int[4];
	private final float[] workingSpace = new float[9];
	private final float[] modelUvs = new float[12];
	private final float[] normal = new float[3];
	private final int[] modelNormals = new int[9];

	private final float[] modelLocalX = new float[MAX_VERTEX_COUNT];
	private final float[] modelLocalY = new float[MAX_VERTEX_COUNT];
	private final float[] modelLocalZ = new float[MAX_VERTEX_COUNT];

	private final int[] modelLocalXI = new int[MAX_VERTEX_COUNT];
	private final int[] modelLocalYI = new int[MAX_VERTEX_COUNT];
	private final int[] modelLocalZI = new int[MAX_VERTEX_COUNT];

	public void setScene(Scene scene) {
		if (scene == currentScene)
			return;

		currentScene = scene;
		tiles = scene.getExtendedTiles();
		settings = scene.getExtendedTileSettings();
		roofs = scene.getRoofs();
		overlayIds = scene.getOverlayIds();
		underlayIds = scene.getUnderlayIds();
		tileHeights = scene.getTileHeights();
	}

	public void clear() {
		tiles = null;
		settings = null;
		roofs = null;
		overlayIds = null;
		underlayIds = null;
		tileHeights = null;
		currentScene = null;
	}

	protected void onBeforeProcessTile(Tile t, boolean isEstimate) {}

	public void estimateZoneSize(ZoneSceneContext ctx, Zone zone, int mzx, int mzz) {
		// Initialize the zone as containing only water, until a non-water tile is found
		zone.onlyWater = true;

		for (int z = 3; z >= 0; --z) {
			for (int xoff = 0; xoff < 8; ++xoff) {
				for (int zoff = 0; zoff < 8; ++zoff) {
					Tile t = tiles[z][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (t != null) {
						onBeforeProcessTile(t, true);
						estimateZoneTileSize(ctx, zone, t);
					}
				}
			}
		}
	}

	public void uploadZone(ZoneSceneContext ctx, Zone zone, int mzx, int mzz) {
		var vb = zone.vboO != null ? new GpuIntBuffer(zone.vboO.vb) : null;
		var ab = zone.vboA != null ? new GpuIntBuffer(zone.vboA.vb) : null;
		var fb = zone.tboF != null ? new GpuIntBuffer(zone.tboF.getPixelBuffer()) : null;
		assert fb != null;

		roofIds.clear();
		for (int level = 0; level <= 3; ++level) {
			for (int xoff = 0; xoff < 8; ++xoff) {
				for (int zoff = 0; zoff < 8; ++zoff) {
					int rid = roofs[level][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (rid > 0)
						roofIds.add(rid);
				}
			}
		}

		zone.rids = new int[4][roofIds.size()];
		zone.roofStart = new int[4][roofIds.size()];
		zone.roofEnd = new int[4][roofIds.size()];

		for (int z = 0; z <= 3; ++z) {
			this.level = z;

			if (z == 0) {
				uploadZoneLevel(ctx, zone, mzx, mzz, 0, false, roofIds, vb, ab, fb);
				uploadZoneLevel(ctx, zone, mzx, mzz, 0, true, roofIds, vb, ab, fb);
				uploadZoneLevel(ctx, zone, mzx, mzz, 1, true, roofIds, vb, ab, fb);
				uploadZoneLevel(ctx, zone, mzx, mzz, 2, true, roofIds, vb, ab, fb);
				uploadZoneLevel(ctx, zone, mzx, mzz, 3, true, roofIds, vb, ab, fb);
			} else {
				uploadZoneLevel(ctx, zone, mzx, mzz, z, false, roofIds, vb, ab, fb);
			}

			if (zone.vboO != null) {
				int pos = zone.vboO.vb.position();
				zone.levelOffsets[z] = pos;
			}
		}

		// Upload water surface tiles to be drawn after everything else
		if (zone.hasWater && vb != null) {
			uploadZoneWater(ctx, zone, mzx, mzz, vb, fb);
			zone.levelOffsets[Zone.LEVEL_WATER_SURFACE] = vb.position();
		}
	}

	private void uploadZoneLevel(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		int level,
		boolean visbelow,
		Set<Integer> roofIds,
		GpuIntBuffer vb,
		GpuIntBuffer ab,
		GpuIntBuffer fb
	) {
		int ridx = 0;

		// upload the roofs and save their positions
		for (int id : roofIds) {
			int pos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			uploadZoneLevelRoof(ctx, zone, mzx, mzz, level, id, visbelow, vb, ab, fb);

			int endpos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			if (endpos > pos) {
				zone.rids[level][ridx] = id;
				zone.roofStart[level][ridx] = pos;
				zone.roofEnd[level][ridx] = endpos;
				++ridx;
			}
		}

		// upload everything else
		uploadZoneLevelRoof(ctx, zone, mzx, mzz, level, 0, visbelow, vb, ab, fb);
	}

	private void uploadZoneLevelRoof(
		ZoneSceneContext ctx,
		Zone zone,
		int mzx,
		int mzz,
		int level,
		int roofId,
		boolean visbelow,
		GpuIntBuffer vb,
		GpuIntBuffer ab,
		GpuIntBuffer fb
	) {
		this.basex = (mzx - (ctx.sceneOffset >> 3)) << 10;
		this.basez = (mzz - (ctx.sceneOffset >> 3)) << 10;

		for (int xoff = 0; xoff < 8; ++xoff) {
			for (int zoff = 0; zoff < 8; ++zoff) {
				int msx = (mzx << 3) + xoff;
				int msz = (mzz << 3) + zoff;

				boolean isbridge = (settings[1][msx][msz] & Constants.TILE_FLAG_BRIDGE) != 0;
				int maplevel = level;
				if (isbridge) {
					++maplevel;
				}

				boolean isvisbelow = maplevel <= 3 && (settings[maplevel][msx][msz] & Constants.TILE_FLAG_VIS_BELOW) != 0;
				int rid;
				if (isvisbelow || maplevel == 0) {
					rid = 0;
				} else {
					rid = roofs[maplevel - 1][msx][msz];
				}

				if (isvisbelow != visbelow) {
					continue;
				}

				if (rid == roofId) {
					Tile t = tiles[level][msx][msz];
					if (t != null) {
						this.rid = rid;
						onBeforeProcessTile(t, false);
						uploadZoneTile(ctx, zone, t, false, false, vb, ab, fb);
					}
				}
			}
		}
	}

	private void uploadZoneWater(ZoneSceneContext ctx, Zone zone, int mzx, int mzz, GpuIntBuffer vb, GpuIntBuffer fb) {
		this.basex = (mzx - (ctx.sceneOffset >> 3)) << 10;
		this.basez = (mzz - (ctx.sceneOffset >> 3)) << 10;

		for (int level = 0; level < MAX_Z; level++) {
			for (int xoff = 0; xoff < 8; ++xoff) {
				for (int zoff = 0; zoff < 8; ++zoff) {
					int msx = (mzx << 3) + xoff;
					int msz = (mzz << 3) + zoff;
					Tile t = tiles[level][msx][msz];
					if (t != null) {
						onBeforeProcessTile(t, false);
						uploadZoneTile(ctx, zone, t, false, true, vb, null, fb);
					}
				}
			}
		}
	}

	private void estimateZoneTileSize(ZoneSceneContext ctx, Zone z, Tile t) {
		var tilePoint = t.getSceneLocation();
		ctx.sceneToWorld(tilePoint.getX(), tilePoint.getY(), t.getPlane(), worldPos);

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null && paint.getNeColor() != HIDDEN_HSL) {
			z.sizeO += 2;
			z.sizeF += 2;

			TileOverride override = tileOverrideManager.getOverride(ctx, t, worldPos);
			WaterType waterType = proceduralGenerator.seasonalWaterType(override, paint.getTexture());
			if (waterType != WaterType.NONE) {
				z.hasWater = true;
				// Since these are surface tiles, they should perhaps technically be in the alpha buffer,
				// but we'll render them in the correct order without needing face sorting,
				// so we might as well use the opaque buffer for simplicity
				z.sizeO += 2;
				z.sizeF += 2;
			} else {
				z.onlyWater = false;
			}
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null) {
			int len = model.getFaceX().length;
			z.sizeO += len;
			z.sizeF += len;

			int tileExX = tilePoint.getX() + ctx.sceneOffset;
			int tileExY = tilePoint.getY() + ctx.sceneOffset;
			int tileZ = t.getRenderLevel();
			int overlayId = OVERLAY_FLAG | overlayIds[tileZ][tileExX][tileExY];
			int underlayId = underlayIds[tileZ][tileExX][tileExY];
			var overlayOverride = tileOverrideManager.getOverride(ctx, t, worldPos, overlayId);
			var underlayOverride = tileOverrideManager.getOverride(ctx, t, worldPos, underlayId);

			final int[] triangleTextures = model.getTriangleTextureId();
			boolean isFallbackWater = false;
			if (triangleTextures != null) {
				for (int textureId : triangleTextures) {
					if (textureId != -1 && proceduralGenerator.seasonalWaterType(TileOverride.NONE, textureId) != WaterType.NONE) {
						isFallbackWater = true;
						break;
					}
				}
			}
			WaterType overlayWaterType = proceduralGenerator.seasonalWaterType(overlayOverride, 0);
			WaterType underlayWaterType = proceduralGenerator.seasonalWaterType(underlayOverride, 0);
			boolean isOverlayWater = overlayWaterType != WaterType.NONE;
			boolean isUnderlayWater = underlayWaterType != WaterType.NONE;
			if (isFallbackWater || isOverlayWater || isUnderlayWater) {
				z.hasWater = true;
				z.sizeO += len;
				z.sizeF += len;
			} else {
				z.onlyWater = false;
			}
		}

		WallObject wallObject = t.getWallObject();
		if (wallObject != null) {
			ModelOverride modelOverride = modelOverrideManager.getOverride(wallObject, worldPos);
			if (!modelOverride.hide) {
				estimateRenderableSize(z, wallObject.getRenderable1(), modelOverride);
				estimateRenderableSize(z, wallObject.getRenderable2(), modelOverride);
			}
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null) {
			ModelOverride modelOverride = modelOverrideManager.getOverride(decorativeObject, worldPos);
			if (!modelOverride.hide) {
				estimateRenderableSize(z, decorativeObject.getRenderable(), modelOverride);
				estimateRenderableSize(z, decorativeObject.getRenderable2(), modelOverride);
			}
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null) {
			ModelOverride modelOverride = modelOverrideManager.getOverride(groundObject, worldPos);
			if (!modelOverride.hide)
				estimateRenderableSize(z, groundObject.getRenderable(), modelOverride);
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null || !gameObject.getSceneMinLocation().equals(t.getSceneLocation()))
				continue;

			if (ModelHash.isTemporaryObject(gameObject.getHash()))
				continue;

			ModelOverride modelOverride = modelOverrideManager.getOverride(gameObject, worldPos);
			if (modelOverride.hide)
				continue;

			estimateRenderableSize(z, gameObject.getRenderable(), modelOverride);
		}

		Tile bridge = t.getBridge();
		if (bridge != null)
			estimateZoneTileSize(ctx, z, bridge);
	}

	private void uploadZoneTile(
		ZoneSceneContext ctx,
		Zone zone,
		Tile t,
		boolean isBridge,
		boolean onlyWaterSurface,
		GpuIntBuffer vertexBuffer,
		GpuIntBuffer alphaBuffer,
		GpuIntBuffer textureBuffer
	) {
		var tilePoint = t.getSceneLocation();
		int tileExX = tilePoint.getX() + ctx.sceneOffset;
		int tileExY = tilePoint.getY() + ctx.sceneOffset;
		int tileZ = t.getRenderLevel();
		ctx.sceneToWorld(tilePoint.getX(), tilePoint.getY(), t.getPlane(), worldPos);

		if (ctx.currentArea != null && !isBridge && !ctx.currentArea.containsPoint(worldPos))
			return;

		boolean drawTile = renderCallbackManager.drawTile(ctx.scene, t);

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null && drawTile) {
			uploadTilePaint(
				ctx,
				t,
				paint,
				onlyWaterSurface,
				tileExX, tileExY, tileZ,
				vertexBuffer,
				textureBuffer,
				tilePoint.getX() * 128 - basex, tilePoint.getY() * 128 - basez
			);
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null && drawTile)
			uploadTileModel(ctx, t, model, onlyWaterSurface, tileExX, tileExY, tileZ, basex, basez, vertexBuffer, textureBuffer);

		if (!onlyWaterSurface)
			uploadZoneTileRenderables(ctx, zone, t, vertexBuffer, alphaBuffer, textureBuffer);

		Tile bridge = t.getBridge();
		if (bridge != null)
			uploadZoneTile(ctx, zone, bridge, true, onlyWaterSurface, vertexBuffer, alphaBuffer, textureBuffer);
	}

	private void uploadZoneTileRenderables(
		ZoneSceneContext ctx,
		Zone zone,
		Tile t,
		GpuIntBuffer vertexBuffer,
		GpuIntBuffer alphaBuffer,
		GpuIntBuffer textureBuffer
	) {
		WallObject wallObject = t.getWallObject();
		if (wallObject != null && renderCallbackManager.drawObject(ctx.scene, wallObject)) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_WALL_OBJECT, wallObject.getId());
			Renderable renderable1 = wallObject.getRenderable1();
			uploadZoneRenderable(
				ctx,
				zone,
				t,
				renderable1,
				uuid,
				HDUtils.convertWallObjectOrientation(wallObject.getOrientationA()),
				0,
				wallObject.getX(),
				wallObject.getZ(),
				wallObject.getY(),
				-1,
				-1,
				-1,
				-1,
				wallObject.getId(),
				vertexBuffer,
				alphaBuffer,
				textureBuffer
			);

			Renderable renderable2 = wallObject.getRenderable2();
			uploadZoneRenderable(
				ctx,
				zone,
				t,
				renderable2,
				uuid,
				HDUtils.convertWallObjectOrientation(wallObject.getOrientationB()),
				0,
				wallObject.getX(),
				wallObject.getZ(),
				wallObject.getY(),
				-1,
				-1,
				-1,
				-1,
				wallObject.getId(),
				vertexBuffer,
				alphaBuffer,
				textureBuffer
			);
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null && renderCallbackManager.drawObject(ctx.scene, decorativeObject)) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_DECORATIVE_OBJECT, decorativeObject.getId());
			int preOrientation = HDUtils.getModelPreOrientation(decorativeObject.getConfig());
			Renderable renderable = decorativeObject.getRenderable();
			uploadZoneRenderable(
				ctx,
				zone,
				t,
				renderable,
				uuid,
				preOrientation,
				0,
				decorativeObject.getX() + decorativeObject.getXOffset(),
				decorativeObject.getZ(),
				decorativeObject.getY() + decorativeObject.getYOffset(),
				-1,
				-1,
				-1,
				-1,
				decorativeObject.getId(),
				vertexBuffer,
				alphaBuffer,
				textureBuffer
			);

			Renderable renderable2 = decorativeObject.getRenderable2();
			uploadZoneRenderable(
				ctx,
				zone,
				t,
				renderable2,
				uuid,
				preOrientation,
				0,
				decorativeObject.getX(),
				decorativeObject.getZ(),
				decorativeObject.getY(),
				-1,
				-1,
				-1,
				-1,
				decorativeObject.getId(),
				vertexBuffer,
				alphaBuffer,
				textureBuffer
			);
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null && renderCallbackManager.drawObject(ctx.scene, groundObject)) {
			Renderable renderable = groundObject.getRenderable();
			uploadZoneRenderable(
				ctx,
				zone,
				t,
				renderable,
				ModelHash.packUuid(ModelHash.TYPE_GROUND_OBJECT, groundObject.getId()),
				HDUtils.getModelPreOrientation(groundObject.getConfig()),
				0,
				groundObject.getX(), groundObject.getZ(), groundObject.getY(),
				-1,
				-1, -1,
				-1,
				groundObject.getId(),
				vertexBuffer,
				alphaBuffer,
				textureBuffer
			);
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null || !renderCallbackManager.drawObject(ctx.scene, gameObject))
				continue;

			if (ModelHash.isTemporaryObject(gameObject.getHash()))
				continue;

			Point min = gameObject.getSceneMinLocation();
			if (!min.equals(t.getSceneLocation()))
				continue;

			Point max = gameObject.getSceneMaxLocation();
			Renderable renderable = gameObject.getRenderable();
			uploadZoneRenderable(
				ctx,
				zone,
				t,
				renderable,
				ModelHash.packUuid(ModelHash.TYPE_GAME_OBJECT, gameObject.getId()),
				HDUtils.getModelPreOrientation(gameObject.getConfig()),
				gameObject.getModelOrientation(),
				gameObject.getX(), gameObject.getZ(), gameObject.getY(),
				min.getX(),
				min.getY(), max.getX(),
				max.getY(),
				gameObject.getId(),
				vertexBuffer,
				alphaBuffer,
				textureBuffer
			);
		}
	}

	private void estimateRenderableSize(Zone z, Renderable r, ModelOverride modelOverride) {
		boolean mightHaveTransparency = modelOverride.mightHaveTransparency;
		Model m = null;
		if (r instanceof Model) {
			m = (Model) r;
		} else if (r instanceof DynamicObject) {
			var dynamic = (DynamicObject) r;
			m = dynamic.getModelZbuf();
			if (dynamic.getRecordedObjectComposition() != null)
				mightHaveTransparency = true;
		}
		if (m == null)
			return;

		int faceCount = m.getFaceCount();
		byte[] transparencies = m.getFaceTransparencies();
		short[] faceTextures = m.getFaceTextures();
		if (transparencies == null && faceTextures == null && !mightHaveTransparency) {
			z.sizeO += faceCount;
			z.sizeF += faceCount;
		} else {
			z.sizeO += faceCount;
			z.sizeA += faceCount;
			z.sizeF += faceCount;
		}
	}

	private void uploadZoneRenderable(
		ZoneSceneContext ctx,
		Zone zone,
		Tile tile,
		Renderable r,
		int uuid,
		int preOrientation,
		int orient,
		int x,
		int y,
		int z,
		int lx,
		int lz,
		int ux,
		int uz,
		int id,
		GpuIntBuffer opaqueBuffer,
		GpuIntBuffer alphaBuffer,
		GpuIntBuffer textureBuffer
	) {
		Model model = null;
		if (r instanceof Model) {
			model = (Model) r;
		} else if (r instanceof DynamicObject) {
			var dynamic = (DynamicObject) r;
			model = dynamic.getModelZbuf();
			var composition = dynamic.getRecordedObjectComposition();
			if (composition != null)
				uuid = ModelHash.packUuid(ModelHash.TYPE_GAME_OBJECT, composition.getId());
		}
		if (model == null)
			return;

		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);
		if (modelOverride.hide)
			return;

		int alphaStart = zone.vboA != null ? zone.vboA.vb.position() : 0;
		try {
			uploadStaticModel(
				ctx, tile, model, modelOverride, uuid,
				preOrientation, orient,
				x - basex, y, z - basez,
				opaqueBuffer,
				alphaBuffer,
				textureBuffer
			);
		} catch (Throwable ex) {
			log.warn(
				"Error uploading {} {} {} {} (ID {}), override=\"{}\", opaque={}, alpha={}",
				r instanceof DynamicObject ? "dynamic" : "static",
				ModelHash.getTypeName(ModelHash.getUuidType(uuid)),
				ModelHash.getUuidSubType(uuid),
				gamevalManager.getObjectName(id),
				id,
				modelOverride.description,
				opaqueBuffer,
				alphaBuffer,
				ex
			);
		}

		int alphaEnd = zone.vboA != null ? zone.vboA.vb.position() : 0;
		if (alphaEnd > alphaStart) {
			if (lx > -1) {
				lx -= basex >> 7;
				lz -= basez >> 7;
				ux -= basex >> 7;
				uz -= basez >> 7;
				assert lx >= 0 : lx;
				assert lz >= 0 : lz;
				assert ux < 25 : ux; // largest object?
				assert uz < 25 : uz;
			}
			try {
				zone.addAlphaModel(
					plugin,
					materialManager,
					zone.glVaoA, zone.tboF.getTexId(), model, modelOverride, alphaStart, alphaEnd,
					x - basex, y, z - basez,
					lx, lz, ux, uz,
					rid, level, id
				);
			} catch (Throwable ex) {
				log.warn(
					"Error adding alpha model for {} {} {} {} (ID {}), override=\"{}\", opaque={}, alpha={}",
					r instanceof DynamicObject ? "dynamic" : "static",
					ModelHash.getTypeName(ModelHash.getUuidType(uuid)),
					ModelHash.getUuidSubType(uuid),
					gamevalManager.getObjectName(id),
					id,
					modelOverride.description,
					opaqueBuffer,
					alphaBuffer,
					ex
				);
			}
		}
	}

	@SuppressWarnings({ "UnnecessaryLocalVariable" })
	private void uploadTilePaint(
		ZoneSceneContext ctx,
		Tile tile,
		SceneTilePaint paint,
		boolean onlyWaterSurface,
		int tileExX, int tileExY, int tileZ,
		GpuIntBuffer vb,
		GpuIntBuffer fb,
		int lx,
		int lz
	) {
		int swColor = paint.getSwColor();
		int seColor = paint.getSeColor();
		int neColor = paint.getNeColor();
		int nwColor = paint.getNwColor();

		if (neColor == HIDDEN_HSL)
			return;

		TileOverride override = tileOverrideManager.getOverride(ctx, tile, worldPos);
		WaterType waterType = proceduralGenerator.seasonalWaterType(override, paint.getTexture());
		if (onlyWaterSurface && waterType == WaterType.NONE)
			return;

		int swHeight = tileHeights[tileZ][tileExX][tileExY];
		int seHeight = tileHeights[tileZ][tileExX + 1][tileExY];
		int neHeight = tileHeights[tileZ][tileExX + 1][tileExY + 1];
		int nwHeight = tileHeights[tileZ][tileExX][tileExY + 1];
		int textureId = paint.getTexture();

		// 0,0
		final int lx0 = lx;
		final int lz0 = lz;

		// 1,0
		final int lx1 = lx + LOCAL_TILE_SIZE;
		final int lz1 = lz;

		// 1,1
		final int lx2 = lx + LOCAL_TILE_SIZE;
		final int lz2 = lz + LOCAL_TILE_SIZE;

		// 0,1
		final int lx3 = lx;
		final int lz3 = lz + LOCAL_TILE_SIZE;

		ProceduralGenerator.tileVertexKeys(ctx, tile, vertices, vertexKeys);
		int swVertexKey = vertexKeys[0];
		int seVertexKey = vertexKeys[1];
		int nwVertexKey = vertexKeys[2];
		int neVertexKey = vertexKeys[3];
		boolean neVertexIsOverlay = false;
		boolean nwVertexIsOverlay = false;
		boolean seVertexIsOverlay = false;
		boolean swVertexIsOverlay = false;

		int uvOrientation = 0;
		float uvScale = 1;

		Material swMaterial = Material.NONE;
		Material seMaterial = Material.NONE;
		Material neMaterial = Material.NONE;
		Material nwMaterial = Material.NONE;

		int[] swNormals = UP_NORMAL;
		int[] seNormals = UP_NORMAL;
		int[] neNormals = UP_NORMAL;
		int[] nwNormals = UP_NORMAL;

		int swTerrainData, seTerrainData, nwTerrainData, neTerrainData;
		swTerrainData = seTerrainData = nwTerrainData = neTerrainData = HDUtils.packTerrainData(true, 0, waterType, tileZ);

		if (!onlyWaterSurface) {
			swNormals = ctx.vertexTerrainNormals.getOrDefault(swVertexKey, swNormals);
			seNormals = ctx.vertexTerrainNormals.getOrDefault(seVertexKey, seNormals);
			neNormals = ctx.vertexTerrainNormals.getOrDefault(neVertexKey, neNormals);
			nwNormals = ctx.vertexTerrainNormals.getOrDefault(nwVertexKey, nwNormals);
		}

		if (waterType == WaterType.NONE) {
			if (textureId != -1) {
				var material = materialManager.fromVanillaTexture(textureId);
				// Disable tile overrides for newly introduced vanilla textures
				if (material.isFallbackVanillaMaterial)
					override = NONE;
				swMaterial = seMaterial = neMaterial = nwMaterial = material;
			}

			boolean useBlendedMaterialAndColor =
				plugin.configGroundBlending &&
				textureId == -1 &&
				!proceduralGenerator.useDefaultColor(tile, override);
			GroundMaterial groundMaterial = null;
			if (override != TileOverride.NONE) {
				groundMaterial = override.groundMaterial;
				uvOrientation = override.uvOrientation;
				uvScale = override.uvScale;
				if (!useBlendedMaterialAndColor) {
					swColor = override.modifyColor(swColor);
					seColor = override.modifyColor(seColor);
					nwColor = override.modifyColor(nwColor);
					neColor = override.modifyColor(neColor);
				}
				swHeight -= override.heightOffset;
				seHeight -= override.heightOffset;
				neHeight -= override.heightOffset;
				nwHeight -= override.heightOffset;
			} else if (textureId == -1) {
				// Fall back to the default ground material if the tile is untextured
				groundMaterial = override.groundMaterial;
			}

			if (useBlendedMaterialAndColor) {
				// get the vertices' colors and textures from hashmaps
				swColor = ctx.vertexTerrainColor.getOrDefault(swVertexKey, swColor);
				seColor = ctx.vertexTerrainColor.getOrDefault(seVertexKey, seColor);
				neColor = ctx.vertexTerrainColor.getOrDefault(neVertexKey, neColor);
				nwColor = ctx.vertexTerrainColor.getOrDefault(nwVertexKey, nwColor);

				if (plugin.configGroundTextures) {
					swMaterial = ctx.vertexTerrainTexture.getOrDefault(swVertexKey, swMaterial);
					seMaterial = ctx.vertexTerrainTexture.getOrDefault(seVertexKey, seMaterial);
					neMaterial = ctx.vertexTerrainTexture.getOrDefault(neVertexKey, neMaterial);
					nwMaterial = ctx.vertexTerrainTexture.getOrDefault(nwVertexKey, nwMaterial);
				}
			} else if (plugin.configGroundTextures && groundMaterial != null) {
				swMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1], worldPos[2]);
				seMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1], worldPos[2]);
				nwMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1] + 1, worldPos[2]);
				neMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1] + 1, worldPos[2]);
			}

			if (ctx.vertexIsOverlay.containsKey(neVertexKey) && ctx.vertexIsUnderlay.containsKey(neVertexKey))
				neVertexIsOverlay = true;
			if (ctx.vertexIsOverlay.containsKey(nwVertexKey) && ctx.vertexIsUnderlay.containsKey(nwVertexKey))
				nwVertexIsOverlay = true;
			if (ctx.vertexIsOverlay.containsKey(seVertexKey) && ctx.vertexIsUnderlay.containsKey(seVertexKey))
				seVertexIsOverlay = true;
			if (ctx.vertexIsOverlay.containsKey(swVertexKey) && ctx.vertexIsUnderlay.containsKey(swVertexKey))
				swVertexIsOverlay = true;
		} else if (onlyWaterSurface) {
			// set colors for the shoreline to create a foam effect in the water shader
			swColor = seColor = nwColor = neColor = 127;

			if (ctx.vertexIsWater.containsKey(swVertexKey) && ctx.vertexIsLand.containsKey(swVertexKey))
				swColor = 0;
			if (ctx.vertexIsWater.containsKey(seVertexKey) && ctx.vertexIsLand.containsKey(seVertexKey))
				seColor = 0;
			if (ctx.vertexIsWater.containsKey(nwVertexKey) && ctx.vertexIsLand.containsKey(nwVertexKey))
				nwColor = 0;
			if (ctx.vertexIsWater.containsKey(neVertexKey) && ctx.vertexIsLand.containsKey(neVertexKey))
				neColor = 0;

			if (seColor == 0 && nwColor == 0 && (neColor == 0 || swColor == 0))
				swColor = seColor = nwColor = neColor = 1 << 16; // Bias depth a bit if it's flush with underwater geometry
		} else {
			// Underwater geometry
			swColor = seColor = neColor = nwColor = UNDERWATER_HSL;

			if (plugin.configGroundTextures) {
				GroundMaterial groundMaterial = GroundMaterial.UNDERWATER_GENERIC;
				swMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1], worldPos[2]);
				seMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1], worldPos[2]);
				nwMaterial = groundMaterial.getRandomMaterial(worldPos[0], worldPos[1] + 1, worldPos[2]);
				neMaterial = groundMaterial.getRandomMaterial(worldPos[0] + 1, worldPos[1] + 1, worldPos[2]);
			}

			int swDepth = ctx.vertexUnderwaterDepth.getOrDefault(swVertexKey, 0);
			int seDepth = ctx.vertexUnderwaterDepth.getOrDefault(seVertexKey, 0);
			int nwDepth = ctx.vertexUnderwaterDepth.getOrDefault(nwVertexKey, 0);
			int neDepth = ctx.vertexUnderwaterDepth.getOrDefault(neVertexKey, 0);
			swHeight += swDepth;
			seHeight += seDepth;
			nwHeight += nwDepth;
			neHeight += neDepth;

			swTerrainData = HDUtils.packTerrainData(true, max(1, swDepth), waterType, tileZ);
			seTerrainData = HDUtils.packTerrainData(true, max(1, seDepth), waterType, tileZ);
			nwTerrainData = HDUtils.packTerrainData(true, max(1, nwDepth), waterType, tileZ);
			neTerrainData = HDUtils.packTerrainData(true, max(1, neDepth), waterType, tileZ);
		}

		int swMaterialData = swMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, swVertexIsOverlay, true);
		int seMaterialData = seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, seVertexIsOverlay, true);
		int nwMaterialData = nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, nwVertexIsOverlay, true);
		int neMaterialData = neMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, neVertexIsOverlay, true);

		float uvcos = -uvScale, uvsin = 0;
		if (uvOrientation % 2048 != 0) {
			float rad = -uvOrientation * JAU_TO_RAD;
			uvcos = cos(rad) * -uvScale;
			uvsin = sin(rad) * -uvScale;
		}
		float uvx = worldPos[0];
		float uvy = worldPos[1];
		float tmp = uvx;
		uvx = fract(uvx * uvcos - uvy * uvsin);
		uvy = fract(tmp * uvsin + uvy * uvcos);

		int texturedFaceIdx = fb.putFace(
			neColor, nwColor, seColor,
			neMaterialData, nwMaterialData, seMaterialData,
			neTerrainData, nwTerrainData, seTerrainData);

		vb.putVertex(
			lx2, neHeight, lz2,
			uvx, uvy, 0,
			neNormals[0], neNormals[2], neNormals[1],
			texturedFaceIdx
		);

		vb.putVertex(
			lx3, nwHeight, lz3,
			uvx - uvcos, uvy - uvsin, 1,
			nwNormals[0], nwNormals[2], nwNormals[1],
			texturedFaceIdx
		);

		vb.putVertex(
			lx1, seHeight, lz1,
			uvx + uvsin, uvy - uvcos, 2,
			seNormals[0], seNormals[2], seNormals[1],
			texturedFaceIdx
		);

		texturedFaceIdx = fb.putFace(
			swColor, seColor, nwColor,
			swMaterialData, seMaterialData, nwMaterialData,
			swTerrainData, seTerrainData, nwTerrainData);

		vb.putVertex(
			lx0, swHeight, lz0,
			uvx - uvcos + uvsin, uvy - uvsin - uvcos, 0,
			swNormals[0], swNormals[2], swNormals[1],
			texturedFaceIdx
		);

		vb.putVertex(
			lx1, seHeight, lz1,
			uvx + uvsin, uvy - uvcos, 1,
			seNormals[0], seNormals[2], seNormals[1],
			texturedFaceIdx
		);

		vb.putVertex(
			lx3, nwHeight, lz3,
			uvx - uvcos, uvy - uvsin, 2,
			nwNormals[0], nwNormals[2], nwNormals[1],
			texturedFaceIdx
		);
	}

	private void uploadTileModel(
		ZoneSceneContext ctx,
		Tile tile,
		SceneTileModel model,
		boolean onlyWaterSurface,
		int tileExX, int tileExY, int tileZ,
		int basex, int basez,
		GpuIntBuffer vb,
		GpuIntBuffer fb
	) {
		final int[] triangleTextures = model.getTriangleTextureId();
		boolean isFallbackWater = false;
		if (triangleTextures != null) {
			for (int textureId : triangleTextures) {
				if (textureId != -1 && proceduralGenerator.seasonalWaterType(TileOverride.NONE, textureId) != WaterType.NONE) {
					isFallbackWater = true;
					break;
				}
			}
		}
		int overlayId = OVERLAY_FLAG | overlayIds[tileZ][tileExX][tileExY];
		int underlayId = underlayIds[tileZ][tileExX][tileExY];
		var overlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, overlayId);
		var underlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, underlayId);
		WaterType overlayWaterType = proceduralGenerator.seasonalWaterType(overlayOverride, 0);
		WaterType underlayWaterType = proceduralGenerator.seasonalWaterType(underlayOverride, 0);
		boolean isOverlayWater = overlayWaterType != WaterType.NONE;
		boolean isUnderlayWater = underlayWaterType != WaterType.NONE;
		if (onlyWaterSurface && !isFallbackWater && !isOverlayWater && !isUnderlayWater)
			return;

		final int[] faceX = model.getFaceX();
		final int[] faceY = model.getFaceY();
		final int[] faceZ = model.getFaceZ();

		final int[] vertexX = model.getVertexX();
		final int[] vertexY = model.getVertexY();
		final int[] vertexZ = model.getVertexZ();

		final int[] triangleColorA = model.getTriangleColorA();
		final int[] triangleColorB = model.getTriangleColorB();
		final int[] triangleColorC = model.getTriangleColorC();

		final int faceCount = faceX.length;

		var sceneLoc = tile.getSceneLocation();
		int tileX = sceneLoc.getX();
		int tileY = sceneLoc.getY();

		for (int face = 0; face < faceCount; ++face) {
			int colorA = triangleColorA[face];
			int colorB = triangleColorB[face];
			int colorC = triangleColorC[face];
			if (colorA == HIDDEN_HSL)
				continue;

			int textureId = triangleTextures == null ? -1 : triangleTextures[face];
			boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
			var override = isOverlay ? overlayOverride : underlayOverride;
			WaterType waterType = proceduralGenerator.seasonalWaterType(override, textureId);
			boolean isWater = waterType != WaterType.NONE;
			if (onlyWaterSurface && !isWater)
				continue;

			final int vertex0 = faceX[face];
			final int vertex1 = faceY[face];
			final int vertex2 = faceZ[face];

			// vertexes are stored in scene local, convert to tile local
			int lx0 = vertexX[vertex0] - basex;
			int ly0 = vertexY[vertex0];
			int lz0 = vertexZ[vertex0] - basez;

			int lx1 = vertexX[vertex1] - basex;
			int ly1 = vertexY[vertex1];
			int lz1 = vertexZ[vertex1] - basez;

			int lx2 = vertexX[vertex2] - basex;
			int ly2 = vertexY[vertex2];
			int lz2 = vertexZ[vertex2] - basez;

			ProceduralGenerator.faceVertexKeys(tile, face, vertices, vertexKeys);
			ProceduralGenerator.faceLocalVertices(tile, face, vertices);

			int vertexKeyA = vertexKeys[0];
			int vertexKeyB = vertexKeys[1];
			int vertexKeyC = vertexKeys[2];

			boolean vertexAIsOverlay = false;
			boolean vertexBIsOverlay = false;
			boolean vertexCIsOverlay = false;

			Material materialA = Material.NONE;
			Material materialB = Material.NONE;
			Material materialC = Material.NONE;

			int uvOrientation = 0;
			float uvScale = 1;

			int[] normalsA = UP_NORMAL;
			int[] normalsB = UP_NORMAL;
			int[] normalsC = UP_NORMAL;

			int terrainDataA, terrainDataB, terrainDataC;
			terrainDataA = terrainDataB = terrainDataC = HDUtils.packTerrainData(true, 0, waterType, tileZ);

			if (!onlyWaterSurface) {
				normalsA = ctx.vertexTerrainNormals.getOrDefault(vertexKeyA, normalsA);
				normalsB = ctx.vertexTerrainNormals.getOrDefault(vertexKeyB, normalsB);
				normalsC = ctx.vertexTerrainNormals.getOrDefault(vertexKeyC, normalsC);
			}

			if (!isWater) {
				if (textureId != -1) {
					var material = materialManager.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material.isFallbackVanillaMaterial)
						override = NONE;
					materialA = materialB = materialC = material;
				}

				GroundMaterial groundMaterial = null;

				boolean useBlendedMaterialAndColor =
					plugin.configGroundBlending &&
					textureId == -1 &&
					!(isOverlay && proceduralGenerator.useDefaultColor(tile, override));
				if (override != TileOverride.NONE) {
					groundMaterial = override.groundMaterial;
					uvOrientation = override.uvOrientation;
					uvScale = override.uvScale;
					if (!useBlendedMaterialAndColor) {
						colorA = override.modifyColor(colorA);
						colorB = override.modifyColor(colorB);
						colorC = override.modifyColor(colorC);
					}
				} else if (textureId == -1) {
					// Fall back to the default ground material if the tile is untextured
					groundMaterial = override.groundMaterial;
				}

				if (useBlendedMaterialAndColor) {
					// get the vertices' colors and textures from hashmaps
					colorA = ctx.vertexTerrainColor.getOrDefault(vertexKeyA, colorA);
					colorB = ctx.vertexTerrainColor.getOrDefault(vertexKeyB, colorB);
					colorC = ctx.vertexTerrainColor.getOrDefault(vertexKeyC, colorC);

					if (plugin.configGroundTextures) {
						materialA = ctx.vertexTerrainTexture.getOrDefault(vertexKeyA, materialA);
						materialB = ctx.vertexTerrainTexture.getOrDefault(vertexKeyB, materialB);
						materialC = ctx.vertexTerrainTexture.getOrDefault(vertexKeyC, materialC);
					}
				} else if (plugin.configGroundTextures && groundMaterial != null) {
					materialA = groundMaterial.getRandomMaterial(
						worldPos[0] + (vertices[0][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (vertices[0][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialB = groundMaterial.getRandomMaterial(
						worldPos[0] + (vertices[1][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (vertices[1][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialC = groundMaterial.getRandomMaterial(
						worldPos[0] + (vertices[2][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (vertices[2][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
				}
			} else if (onlyWaterSurface) {
				// set colors for the shoreline to create a foam effect in the water shader
				colorA = colorB = colorC = 127;
				if (ctx.vertexIsWater.containsKey(vertexKeyA) && ctx.vertexIsLand.containsKey(vertexKeyA))
					colorA = 0;
				if (ctx.vertexIsWater.containsKey(vertexKeyB) && ctx.vertexIsLand.containsKey(vertexKeyB))
					colorB = 0;
				if (ctx.vertexIsWater.containsKey(vertexKeyC) && ctx.vertexIsLand.containsKey(vertexKeyC))
					colorC = 0;
				if (colorA == 0 && colorB == 0 && colorC == 0)
					colorA = colorB = colorC = 1 << 16; // Bias depth a bit if it's flush with underwater geometry
			} else {
				// Underwater geometry
				colorA = colorB = colorC = UNDERWATER_HSL;

				if (plugin.configGroundTextures) {
					GroundMaterial groundMaterial = GroundMaterial.UNDERWATER_GENERIC;
					materialA = groundMaterial.getRandomMaterial(
						worldPos[0] + (vertices[0][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (vertices[0][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialB = groundMaterial.getRandomMaterial(
						worldPos[0] + (vertices[1][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (vertices[1][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialC = groundMaterial.getRandomMaterial(
						worldPos[0] + (vertices[2][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (vertices[2][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
				}

				int depthA = ctx.vertexUnderwaterDepth.getOrDefault(vertexKeyA, 0);
				int depthB = ctx.vertexUnderwaterDepth.getOrDefault(vertexKeyB, 0);
				int depthC = ctx.vertexUnderwaterDepth.getOrDefault(vertexKeyC, 0);
				ly0 += depthA;
				ly1 += depthB;
				ly2 += depthC;

				terrainDataA = HDUtils.packTerrainData(true, max(1, depthA), waterType, tileZ);
				terrainDataB = HDUtils.packTerrainData(true, max(1, depthB), waterType, tileZ);
				terrainDataC = HDUtils.packTerrainData(true, max(1, depthC), waterType, tileZ);
			}

			if (ctx.vertexIsOverlay.containsKey(vertexKeyA) && ctx.vertexIsUnderlay.containsKey(vertexKeyA))
				vertexAIsOverlay = true;
			if (ctx.vertexIsOverlay.containsKey(vertexKeyB) && ctx.vertexIsUnderlay.containsKey(vertexKeyB))
				vertexBIsOverlay = true;
			if (ctx.vertexIsOverlay.containsKey(vertexKeyC) && ctx.vertexIsUnderlay.containsKey(vertexKeyC))
				vertexCIsOverlay = true;

			ly0 -= override.heightOffset;
			ly1 -= override.heightOffset;
			ly2 -= override.heightOffset;

			int materialDataA = materialA.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexAIsOverlay, true);
			int materialDataB = materialB.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexBIsOverlay, true);
			int materialDataC = materialC.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexCIsOverlay, true);

			float uvcos = -uvScale, uvsin = 0;
			if (uvOrientation % 2048 != 0) {
				float rad = -uvOrientation * JAU_TO_RAD;
				uvcos = cos(rad) * -uvScale;
				uvsin = sin(rad) * -uvScale;
			}

			float uvx = fract(worldPos[0] * uvcos - worldPos[1] * uvsin);
			float uvy = fract(worldPos[0] * uvsin + worldPos[1] * uvcos);

			float dx, dz;
			dx = (float) lx0 / LOCAL_TILE_SIZE - tileX;
			dz = (float) lz0 / LOCAL_TILE_SIZE - tileY;
			float uvAx = uvx + dx * uvcos - dz * uvsin;
			float uvAy = uvy + dx * uvsin + dz * uvcos;
			dx = (float) lx1 / LOCAL_TILE_SIZE - tileX;
			dz = (float) lz1 / LOCAL_TILE_SIZE - tileY;
			float uvBx = uvx + dx * uvcos - dz * uvsin;
			float uvBy = uvy + dx * uvsin + dz * uvcos;
			dx = (float) lx2 / LOCAL_TILE_SIZE - tileX;
			dz = (float) lz2 / LOCAL_TILE_SIZE - tileY;
			float uvCx = uvx + dx * uvcos - dz * uvsin;
			float uvCy = uvy + dx * uvsin + dz * uvcos;

			int texturedFaceIdx = fb.putFace(
				colorA, colorB, colorC,
				materialDataA, materialDataB, materialDataC,
				terrainDataA, terrainDataB, terrainDataC);

			vb.putVertex(
				lx0, ly0, lz0,
				uvAx, uvAy, 0,
				normalsA[0], normalsA[2], normalsA[1],
				texturedFaceIdx
			);

			vb.putVertex(
				lx1, ly1, lz1,
				uvBx, uvBy, 1,
				normalsB[0], normalsB[2], normalsB[1],
				texturedFaceIdx
			);

			vb.putVertex(
				lx2, ly2, lz2,
				uvCx, uvCy, 2,
				normalsC[0], normalsC[2], normalsC[1],
				texturedFaceIdx
			);
		}
	}

	// scene upload
	private int uploadStaticModel(
		ZoneSceneContext ctx,
		Tile tile,
		Model model,
		ModelOverride modelOverride,
		int uuid,
		int preOrientation, int orientation,
		int x, int y, int z,
		GpuIntBuffer opaqueBuffer,
		GpuIntBuffer alphaBuffer,
		GpuIntBuffer textureBuffer
	) {
		final int[][][] tileHeights = ctx.scene.getTileHeights();
		final int faceCount = model.getFaceCount();
		final int vertexCount = model.getVerticesCount();

		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final short[] unlitFaceColors = plugin.configUnlitFaceColors ? model.getUnlitFaceColors() : null;
		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final int[] xVertexNormals = model.getVertexNormalsX();
		final int[] yVertexNormals = model.getVertexNormalsY();
		final int[] zVertexNormals = model.getVertexNormalsZ();
		final boolean modelHasNormals = xVertexNormals != null && yVertexNormals != null && zVertexNormals != null;

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();

		final byte[] bias = model.getFaceBias();
		final byte[] transparencies = model.getFaceTransparencies();
		final float modelHeight = model.getModelHeight();

		int orientSin = 0;
		int orientCos = 0;
		if (orientation != 0) {
			orientation = mod(orientation, 2048);
			orientSin = SINE[orientation];
			orientCos = COSINE[orientation];
		}

		for (int v = 0; v < vertexCount; ++v) {
			int vx = (int) vertexX[v];
			int vy = (int) vertexY[v];
			int vz = (int) vertexZ[v];
			float heightFrac = modelOverride.terrainVertexSnap ? abs(vy / modelHeight) : 0.0f;

			if (orientation != 0) {
				int x0 = vx;
				vx = vz * orientSin + x0 * orientCos >> 16;
				vz = vz * orientCos - x0 * orientSin >> 16;
			}

			vx += x;
			vy += y;
			vz += z;

			if (modelOverride.terrainVertexSnap && heightFrac <= modelOverride.terrainVertexSnapThreshold) {
				int plane = tile.getRenderLevel();
				int tileExX = clamp(ctx.sceneOffset + ((vx + basex) / 128), 0, EXTENDED_SCENE_SIZE - 1);
				int tileExY = clamp(ctx.sceneOffset + ((vz + basez) / 128), 0, EXTENDED_SCENE_SIZE - 1);

				float h00 = tileHeights[plane][tileExX][tileExY];
				float h10 = tileHeights[plane][tileExX + 1][tileExY];
				float h01 = tileHeights[plane][tileExX][tileExY + 1];
				float h11 = tileHeights[plane][tileExX + 1][tileExY + 1];

				float hx0 = mix(h00, h10, (vx % 128.0f) / 128.0f);
				float hx1 = mix(h01, h11, (vx % 128.0f) / 128.0f);
				float h = mix(hx0, hx1, (vz % 128.0f) / 128.0f);

				float blend = divide(heightFrac, modelOverride.terrainVertexSnapThreshold);
				vy = (int) mix(h, vy, blend);
			}

			modelLocalXI[v] = vx;
			modelLocalYI[v] = vy;
			modelLocalZI[v] = vz;
		}

		boolean isVanillaTextured = faceTextures != null;
		boolean isVanillaUVMapped =
			isVanillaTextured && // Vanilla UV mapped models don't always have sensible UVs for untextured faces
			textureFaces != null;

		final Material baseMaterial = modelOverride.baseMaterial;
		final Material textureMaterial = modelOverride.textureMaterial;

		int len = 0;
		for (int face = 0; face < faceCount; ++face) {
			int color1 = color1s[face];
			int color2 = color2s[face];
			int color3 = color3s[face];

			if (color3 == -1) {
				color2 = color3 = color1;
			} else if (color3 == -2) {
				continue;
			}

			if (unlitFaceColors != null)
				color1 = color2 = color3 = unlitFaceColors[face] & 0xFFFF;

			int triangleA = indices1[face];
			int triangleB = indices2[face];
			int triangleC = indices3[face];

			int vx1 = modelLocalXI[triangleA];
			int vy1 = modelLocalYI[triangleA];
			int vz1 = modelLocalZI[triangleA];

			int vx2 = modelLocalXI[triangleB];
			int vy2 = modelLocalYI[triangleB];
			int vz2 = modelLocalZI[triangleB];

			int vx3 = modelLocalXI[triangleC];
			int vy3 = modelLocalYI[triangleC];
			int vz3 = modelLocalZI[triangleC];

			int textureFace = textureFaces != null ? textureFaces[face] : -1;
			int transparency = transparencies != null ? transparencies[face] & 0xFF : 0;
			int textureId = isVanillaTextured ? faceTextures[face] : -1;
			boolean isTextured = textureId != -1;
			boolean keepShading = isTextured;
			if (isTextured) {
				// Without overriding the color for textured faces, vanilla shading remains pretty noticeable even after
				// the approximate reversal above. Ardougne rooftops is a good example, where vanilla shading results in a
				// weird-looking tint. The brightness clamp afterward is required to reduce the over-exposure introduced.
				color1 = color2 = color3 = 90;
			} else {
				// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
				if (plugin.configHideFakeShadows && modelOverride.hideVanillaShadows && HDUtils.isBakedGroundShading(model, face))
					continue;

				if (modelOverride.inheritTileColorType != InheritTileColorType.NONE) {
					final Scene scene = ctx.scene;
					SceneTileModel tileModel = tile.getSceneTileModel();
					SceneTilePaint tilePaint = tile.getSceneTilePaint();

					if (tilePaint != null || tileModel != null) {
						// No point in inheriting tilepaint color if the ground tile does not have a color, for example above a cave wall
						if (
							tilePaint != null &&
							tilePaint.getTexture() == -1 &&
							tilePaint.getRBG() != 0 &&
							tilePaint.getNeColor() != HIDDEN_HSL
						) {
							// Since tile colors are guaranteed to have the same hue and saturation per face,
							// we can blend without converting from HSL to RGB
							int averageColor =
								(tilePaint.getSwColor() + tilePaint.getNwColor() + tilePaint.getNeColor() + tilePaint.getSeColor()) / 4;

							var override = tileOverrideManager.getOverride(ctx, tile);
							averageColor = override.modifyColor(averageColor);
							color1 = color2 = color3 = averageColor;

							// Let the shader know vanilla shading reversal should be skipped for this face
							keepShading = true;
						} else if (tileModel != null && tileModel.getTriangleTextureId() == null) {
							int faceColorIndex = -1;
							for (int i = 0; i < tileModel.getTriangleColorA().length; i++) {
								boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, i);
								// Use underlay if the tile does not have an overlay, useful for rocks in cave corners.
								if (modelOverride.inheritTileColorType == InheritTileColorType.UNDERLAY
									|| tileModel.getModelOverlay() == 0) {
									// pulling the color from UNDERLAY is more desirable for green grass tiles
									// OVERLAY pulls in path color which is not desirable for grass next to paths
									if (!isOverlay) {
										faceColorIndex = i;
										break;
									}
								} else if (modelOverride.inheritTileColorType == InheritTileColorType.OVERLAY) {
									if (isOverlay) {
										// OVERLAY used in dirt/path/house tile color blend better with rubbles/rocks
										faceColorIndex = i;
										break;
									}
								}
							}

							if (faceColorIndex != -1) {
								int color = tileModel.getTriangleColorA()[faceColorIndex];
								if (color != HIDDEN_HSL) {
									var scenePos = tile.getSceneLocation();
									int tileX = scenePos.getX();
									int tileY = scenePos.getY();
									int tileZ = tile.getRenderLevel();
									int tileExX = tileX + ctx.sceneOffset;
									int tileExY = tileY + ctx.sceneOffset;
									int tileId = modelOverride.inheritTileColorType == InheritTileColorType.OVERLAY ?
										OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY] :
										scene.getUnderlayIds()[tileZ][tileExX][tileExY];
									var override = tileOverrideManager.getOverride(ctx, tile, worldPos, tileId);
									color = override.modifyColor(color);
									color1 = color2 = color3 = color;

									// Let the shader know vanilla shading reversal should be skipped for this face
									keepShading = true;
								}
							}
						}
					}
				}

				if (plugin.configLegacyTzHaarReskin && modelOverride.tzHaarRecolorType != TzHaarRecolorType.NONE) {
					int[] tzHaarRecolored = ProceduralGenerator.recolorTzHaar(
						uuid,
						modelOverride,
						model,
						face,
						color1,
						color2,
						color3
					);
					color1 = tzHaarRecolored[0];
					color2 = tzHaarRecolored[1];
					color3 = tzHaarRecolored[2];
					transparency |= tzHaarRecolored[3];
				}
			}

			UvType uvType = UvType.GEOMETRY;
			Material material = baseMaterial;
			ModelOverride faceOverride = modelOverride;

			if (isTextured) {
				uvType = UvType.VANILLA;
				if (textureMaterial != Material.NONE) {
					material = textureMaterial;
				} else {
					material = materialManager.fromVanillaTexture(textureId);
					if (modelOverride.materialOverrides != null) {
						var override = modelOverride.materialOverrides.get(material);
						if (override != null) {
							faceOverride = override;
							material = faceOverride.textureMaterial;
						}
					}
				}
			} else if (modelOverride.colorOverrides != null) {
				int ahsl = (0xFF - transparency) << 16 | color1;
				for (var override : modelOverride.colorOverrides) {
					if (override.ahslCondition.test(ahsl)) {
						faceOverride = override;
						material = faceOverride.baseMaterial;
						break;
					}
				}
			}

			if (material != Material.NONE) {
				uvType = faceOverride.uvType;
				if (uvType == UvType.VANILLA || (textureId != -1 && faceOverride.retainVanillaUvs))
					uvType = isVanillaUVMapped && textureFace != -1 ? UvType.VANILLA : UvType.GEOMETRY;
			}

			int materialData = material.packMaterialData(faceOverride, uvType, false, false);

			final float[] faceUVs;
			if (uvType == UvType.VANILLA) {
				if (uvType.worldUvs) {
					computeWorldUvsInline(
						faceUVs = modelUvs,
						vx1, vy1, vz1,
						vx2, vy2, vz2,
						vx3, vy3, vz3
					);
				} else {
					if(textureId != -1) {
						computeFaceUvsInline(model, textureFace, triangleA, triangleB, triangleC, faceUVs = modelUvs);
					} else {
						faceUVs = IDENTITY_UV;
					}
				}
			} else {
				if(uvType != UvType.GEOMETRY) {
					faceOverride.fillUvsForFace(faceUVs = modelUvs, model, preOrientation, uvType, face, workingSpace);
				} else {
					faceUVs = IDENTITY_UV;
				}
			}

			final boolean shouldCalculateFaceNormal;
			if (modelHasNormals) {
				if (faceOverride.flatNormals || (!plugin.configPreserveVanillaNormals && color3s[face] == -1)) {
					shouldCalculateFaceNormal = true;
				} else {
					modelNormals[0] = xVertexNormals[triangleA];
					modelNormals[1] = yVertexNormals[triangleA];
					modelNormals[2] = zVertexNormals[triangleA];
					modelNormals[3] = xVertexNormals[triangleB];
					modelNormals[4] = yVertexNormals[triangleB];
					modelNormals[5] = zVertexNormals[triangleB];
					modelNormals[6] = xVertexNormals[triangleC];
					modelNormals[7] = yVertexNormals[triangleC];
					modelNormals[8] = zVertexNormals[triangleC];

					shouldCalculateFaceNormal =
						modelNormals[0] == 0 && modelNormals[1] == 0 && modelNormals[2] == 0 &&
						modelNormals[3] == 0 && modelNormals[4] == 0 && modelNormals[5] == 0 &&
						modelNormals[6] == 0 && modelNormals[7] == 0 && modelNormals[8] == 0;
				}
			} else {
				shouldCalculateFaceNormal = true;
			}

			if(shouldCalculateFaceNormal) {
				calculateFaceNormal(
					vx1, vy1, vz1,
					vx2, vy2, vz2,
					vx3, vy3, vz3,
					modelNormals);
			}

			if (plugin.configUndoVanillaShading && modelOverride.undoVanillaShading && !keepShading) {
				color1 = undoVanillaShading(color1, plugin.configLegacyGreyColors, modelNormals[0], modelNormals[1], modelNormals[2]);
				color2 = undoVanillaShading(color2, plugin.configLegacyGreyColors, modelNormals[3], modelNormals[4], modelNormals[5]);
				color3 = undoVanillaShading(color3, plugin.configLegacyGreyColors, modelNormals[6], modelNormals[7], modelNormals[8]);
			}

			int depthBias = faceOverride.depthBias != -1 ? faceOverride.depthBias :
				bias == null ? 0 : bias[face] & 0xFF;
			int packedAlphaBiasHsl = transparency << 24 | depthBias << 16;
			boolean hasAlpha = material.hasTransparency || transparency != 0;
			GpuIntBuffer vb = hasAlpha ? alphaBuffer : opaqueBuffer;

			color1 |= packedAlphaBiasHsl;
			color2 |= packedAlphaBiasHsl;
			color3 |= packedAlphaBiasHsl;

			final int texturedFaceIdx = textureBuffer.putFace(
				color1, color2, color3,
				materialData, materialData, materialData,
				0, 0, 0
			);

			vb.putVertex(
				vx1, vy1, vz1,
				faceUVs[0], faceUVs[1], 0,
				modelNormals[0], modelNormals[1], modelNormals[2],
				texturedFaceIdx
			);

			vb.putVertex(
				vx2, vy2, vz2,
				faceUVs[4], faceUVs[5], 1,
				modelNormals[3], modelNormals[4], modelNormals[5],
				texturedFaceIdx
			);

			vb.putVertex(
				vx3, vy3, vz3,
				faceUVs[8], faceUVs[9], 2,
				modelNormals[6], modelNormals[7], modelNormals[8],
				texturedFaceIdx
			);

			len += 3;
		}

		return len;
	}

	// temp draw
	public int uploadTempModel(
		Model model,
		ModelOverride modelOverride,
		int preOrientation,
		int orientation,
		int x,
		int y,
		int z,
		IntBuffer opaqueBuffer,
		IntBuffer alphaBuffer,
		IntBuffer opaqueTexBuffer,
		IntBuffer alphaTexBuffer
	) {
		final int triangleCount = model.getFaceCount();
		final int vertexCount = model.getVerticesCount();

		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final short[] unlitFaceColors = plugin.configUnlitFaceColors ? model.getUnlitFaceColors() : null;
		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final int[] xVertexNormals = model.getVertexNormalsX();
		final int[] yVertexNormals = model.getVertexNormalsY();
		final int[] zVertexNormals = model.getVertexNormalsZ();
		final boolean modelHasNormals = xVertexNormals != null && yVertexNormals != null && zVertexNormals != null;

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();

		final byte[] bias = model.getFaceBias();
		final byte[] transparencies = model.getFaceTransparencies();

		final byte overrideAmount = model.getOverrideAmount();
		final byte overrideHue = model.getOverrideHue();
		final byte overrideSat = model.getOverrideSaturation();
		final byte overrideLum = model.getOverrideLuminance();

		float orientSine = 0;
		float orientCosine = 0;
		if (orientation != 0) {
			orientation = mod(orientation, 2048);
			orientSine = SINE[orientation] / 65536f;
			orientCosine = COSINE[orientation] / 65536f;
		}

		for (int v = 0; v < vertexCount; ++v) {
			float vertexX = verticesX[v];
			float vertexY = verticesY[v];
			float vertexZ = verticesZ[v];

			if (orientation != 0) {
				float x0 = vertexX;
				vertexX = vertexZ * orientSine + x0 * orientCosine;
				vertexZ = vertexZ * orientCosine - x0 * orientSine;
			}

			vertexX += x;
			vertexY += y;
			vertexZ += z;

			modelLocalX[v] = vertexX;
			modelLocalY[v] = vertexY;
			modelLocalZ[v] = vertexZ;
		}

		boolean isVanillaTextured = faceTextures != null;
		boolean isVanillaUVMapped =
			isVanillaTextured && // Vanilla UV mapped models don't always have sensible UVs for untextured faces
			textureFaces != null;

		Material baseMaterial = modelOverride.baseMaterial;
		Material textureMaterial = modelOverride.textureMaterial;

		int len = 0;
		for (int face = 0; face < triangleCount; ++face) {
			int transparency = transparencies != null ? transparencies[face] & 0xFF : 0;
			if (transparency == 255)
				continue;

			int color1 = color1s[face];
			int color2 = color2s[face];
			int color3 = color3s[face];

			if (color3 == -1) {
				color2 = color3 = color1;
			} else if (color3 == -2) {
				continue;
			}

			// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
			if (plugin.configHideFakeShadows && modelOverride.hideVanillaShadows && HDUtils.isBakedGroundShading(model, face))
				continue;

			if (unlitFaceColors != null)
				color1 = color2 = color3 = unlitFaceColors[face] & 0xFFFF;

			int triangleA = indices1[face];
			int triangleB = indices2[face];
			int triangleC = indices3[face];

			float vx1 = modelLocalX[triangleA];
			float vy1 = modelLocalY[triangleA];
			float vz1 = modelLocalZ[triangleA];

			float vx2 = modelLocalX[triangleB];
			float vy2 = modelLocalY[triangleB];
			float vz2 = modelLocalZ[triangleB];

			float vx3 = modelLocalX[triangleC];
			float vy3 = modelLocalY[triangleC];
			float vz3 = modelLocalZ[triangleC];

			int textureFace = textureFaces != null ? textureFaces[face] : -1;
			int textureId = isVanillaTextured ? faceTextures[face] : -1;
			UvType uvType = UvType.GEOMETRY;
			Material material = baseMaterial;
			ModelOverride faceOverride = modelOverride;

			if (textureId != -1) {
				color1 = color2 = color3 = 90;
				uvType = UvType.VANILLA;
				if (textureMaterial != Material.NONE) {
					material = textureMaterial;
				} else {
					material = materialManager.fromVanillaTexture(textureId);
					if (modelOverride.materialOverrides != null) {
						var override = modelOverride.materialOverrides.get(material);
						if (override != null) {
							faceOverride = override;
							material = faceOverride.textureMaterial;
						}
					}
				}
			} else if (modelOverride.colorOverrides != null) {
				int ahsl = (0xFF - transparency) << 16 | color1;
				for (var override : modelOverride.colorOverrides) {
					if (override.ahslCondition.test(ahsl)) {
						faceOverride = override;
						material = faceOverride.baseMaterial;
						break;
					}
				}
			}

			if (material != Material.NONE) {
				uvType = faceOverride.uvType;
				if (uvType == UvType.VANILLA || (textureId != -1 && faceOverride.retainVanillaUvs))
					uvType = isVanillaUVMapped && textureFace != -1 ? UvType.VANILLA : UvType.GEOMETRY;
			}

			int materialData = material.packMaterialData(faceOverride, uvType, false, textureId != -1);

			final float[] faceUVs;
			if (uvType == UvType.VANILLA) {
				if (uvType.worldUvs) {
					computeWorldUvsInline(
						faceUVs = modelUvs,
						vx1, vy1, vz1,
						vx2, vy2, vz2,
						vx3, vy3, vz3
					);
				} else {
					if(textureId != -1) {
						computeFaceUvsInline(model, textureFace, triangleA, triangleB, triangleC, faceUVs = modelUvs);
					} else {
						faceUVs = IDENTITY_UV;
					}
				}
			} else {
				if(uvType != UvType.GEOMETRY) {
					faceOverride.fillUvsForFace(faceUVs = modelUvs, model, preOrientation, uvType, face, workingSpace);
				} else {
					faceUVs = IDENTITY_UV;
				}
			}

			final boolean shouldCalculateFaceNormal;
			if (modelHasNormals) {
				if (faceOverride.flatNormals || (!plugin.configPreserveVanillaNormals && color3s[face] == -1)) {
					shouldCalculateFaceNormal = true;
				} else {
					modelNormals[0] = xVertexNormals[triangleA];
					modelNormals[1] = yVertexNormals[triangleA];
					modelNormals[2] = zVertexNormals[triangleA];
					modelNormals[3] = xVertexNormals[triangleB];
					modelNormals[4] = yVertexNormals[triangleB];
					modelNormals[5] = zVertexNormals[triangleB];
					modelNormals[6] = xVertexNormals[triangleC];
					modelNormals[7] = yVertexNormals[triangleC];
					modelNormals[8] = zVertexNormals[triangleC];
					shouldCalculateFaceNormal =
						modelNormals[0] == 0 && modelNormals[1] == 0 && modelNormals[2] == 0 &&
						modelNormals[3] == 0 && modelNormals[4] == 0 && modelNormals[5] == 0 &&
						modelNormals[6] == 0 && modelNormals[7] == 0 && modelNormals[8] == 0;
				}
			} else {
				shouldCalculateFaceNormal = true;
			}

			if(shouldCalculateFaceNormal) {
				calculateFaceNormal(
					vx1, vy1, vz1,
					vx2, vy2, vz2,
					vx3, vy3, vz3,
					modelNormals);
			}

			if (plugin.configUndoVanillaShading && modelOverride.undoVanillaShading) {
				color1 = undoVanillaShading(color1, plugin.configLegacyGreyColors, modelNormals[0], modelNormals[1], modelNormals[2]);
				color2 = undoVanillaShading(color2, plugin.configLegacyGreyColors, modelNormals[3], modelNormals[4], modelNormals[5]);
				color3 = undoVanillaShading(color3, plugin.configLegacyGreyColors, modelNormals[6], modelNormals[7], modelNormals[8]);
			}

			// HSL override is not applied to textured faces
			if (overrideAmount > 0 && (!isVanillaTextured || faceTextures[face] == -1)) {
				color1 = interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
				color2 = interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
				color3 = interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}

			int depthBias = faceOverride.depthBias != -1 ? faceOverride.depthBias :
				bias == null ? 0 : bias[face] & 0xFF;
			int packedAlphaBiasHsl = transparency << 24 | depthBias << 16;
			boolean hasAlpha = material.hasTransparency || transparency != 0;
			IntBuffer vb = hasAlpha ? alphaBuffer : opaqueBuffer;
			IntBuffer tb = hasAlpha ? alphaTexBuffer : opaqueTexBuffer;

			color1 |= packedAlphaBiasHsl;
			color2 |= packedAlphaBiasHsl;
			color3 |= packedAlphaBiasHsl;

			final int texturedFaceIdx = GpuIntBuffer.putFace(tb,
				color1, color2, color3,
				materialData, materialData, materialData,
				0, 0, 0
			);

			GpuIntBuffer.putFloatVertex(
				vb,
				vx1, vy1, vz1,
				faceUVs[0], faceUVs[1], 0,
				modelNormals[0], modelNormals[1], modelNormals[2],
				texturedFaceIdx
			);
			GpuIntBuffer.putFloatVertex(
				vb,
				vx2, vy2, vz2,
				faceUVs[4], faceUVs[5], 1,
				modelNormals[3], modelNormals[4], modelNormals[5],
				texturedFaceIdx
			);
			GpuIntBuffer.putFloatVertex(
				vb,
				vx3, vy3, vz3,
				faceUVs[8], faceUVs[9], 2,
				modelNormals[6], modelNormals[7], modelNormals[8],
				texturedFaceIdx
			);
			len += 3;
		}
		return len;
	}

	public static void calculateFaceNormal(
		float vx1, float vy1, float vz1,
		float vx2, float vy2, float vz2,
		float vx3, float vy3, float vz3,
		int[] modelNormals) {
		float e0_x = vx2 - vx1;
		float e0_y = vy2 - vy1;
		float e0_z = vz2 - vz1;

		float e1_x = vx3 - vx1;
		float e1_y = vy3 - vy1;
		float e1_z = vz3 - vz1;

		float nx = e0_y * e1_z - e0_z * e1_y;
		float ny = e0_z * e1_x - e0_x * e1_z;
		float nz = e0_x * e1_y - e0_y * e1_x;

		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		nx /= length;
		ny /= length;
		nz /= length;

		modelNormals[0] = modelNormals[3] = modelNormals[6] = (int) (nx * 2048.0);
		modelNormals[1] = modelNormals[4] = modelNormals[7] = (int) (ny * 2048.0);
		modelNormals[2] = modelNormals[5] = modelNormals[8] = (int) (nz * 2048.0);
	}

	public static int interpolateHSL(int hsl, byte hue2, byte sat2, byte lum2, byte lerp) {
		int hue = hsl >> 10 & 63;
		int sat = hsl >> 7 & 7;
		int lum = hsl & 127;
		int lerpInt = lerp & 255;
		if (hue2 != -1)
			hue += lerpInt * (hue2 - hue) >> 7;
		if (sat2 != -1)
			sat += lerpInt * (sat2 - sat) >> 7;
		if (lum2 != -1)
			lum += lerpInt * (lum2 - lum) >> 7;
		return (hue << 10 | sat << 7 | lum) & 65535;
	}

	public static void computeFaceUvsInline(
		Model model,
		int textureFace,
		int triangleA,
		int triangleB,
		int triangleC,
		float[] modelUvs
	) {
		final float[] vx = model.getVerticesX();
		final float[] vy = model.getVerticesY();
		final float[] vz = model.getVerticesZ();

		// v1
		int texFaceIdx = model.getTexIndices1()[textureFace & 0xFF];
		final float v1x = vx[texFaceIdx];
		final float v1y = vy[texFaceIdx];
		final float v1z = vz[texFaceIdx];

		// v2, v3
		texFaceIdx = model.getTexIndices2()[textureFace & 0xFF];
		final float v2x = vx[texFaceIdx] - v1x;
		final float v2y = vy[texFaceIdx] - v1y;
		final float v2z = vz[texFaceIdx] - v1z;

		texFaceIdx = model.getTexIndices3()[textureFace & 0xFF];
		final float v3x = vx[texFaceIdx] - v1x;
		final float v3y = vy[texFaceIdx] - v1y;
		final float v3z = vz[texFaceIdx] - v1z;

		// v4, v5, v6
		final float v4x = vx[triangleA] - v1x;
		final float v4y = vy[triangleA] - v1y;
		final float v4z = vz[triangleA] - v1z;

		final float v5x = vx[triangleB] - v1x;
		final float v5y = vy[triangleB] - v1y;
		final float v5z = vz[triangleB] - v1z;

		final float v6x = vx[triangleC] - v1x;
		final float v6y = vy[triangleC] - v1y;
		final float v6z = vz[triangleC] - v1z;

		// v7 = v2 x v3
		final float v7x = v2y * v3z - v2z * v3y;
		final float v7y = v2z * v3x - v2x * v3z;
		final float v7z = v2x * v3y - v2y * v3x;

		// --- U axis ---
		float px = v3y * v7z - v3z * v7y;
		float py = v3z * v7x - v3x * v7z;
		float pz = v3x * v7y - v3y * v7x;

		float inv = 1.0f / (px * v2x + py * v2y + pz * v2z);

		modelUvs[0] = (px * v4x + py * v4y + pz * v4z) * inv;
		modelUvs[4] = (px * v5x + py * v5y + pz * v5z) * inv;
		modelUvs[8] = (px * v6x + py * v6y + pz * v6z) * inv;

		// --- V axis ---
		px = v2y * v7z - v2z * v7y;
		py = v2z * v7x - v2x * v7z;
		pz = v2x * v7y - v2y * v7x;

		inv = 1.0f / (px * v3x + py * v3y + pz * v3z);

		modelUvs[1] = (px * v4x + py * v4y + pz * v4z) * inv;
		modelUvs[5] = (px * v5x + py * v5y + pz * v5z) * inv;
		modelUvs[9] = (px * v6x + py * v6y + pz * v6z) * inv;

		// Z unused
		modelUvs[2] = 0f;
		modelUvs[6] = 0f;
		modelUvs[10] = 0f;
	}

	public static int undoVanillaShading(int color, boolean legacyGreyColors, float nx, float ny, float nz) {
		int h = color >> 10 & 0x3F;
		int s = color >> 7 & 0x7;
		int l = color & 0x7F;

		// Approximately invert vanilla shading by brightening vertices that were likely darkened by vanilla based on
		// vertex normals. This process is error-prone, as not all models are lit by vanilla with the same light
		// direction, and some models even have baked lighting built into the model itself. In some cases, increasing
		// brightness in this way leads to overly bright colors, so we are forced to cap brightness at a relatively
		// low value for it to look acceptable in most cases.
		final float[] L = LIGHT_DIR_MODEL;
		final float color1Adjust =
			BASE_LIGHTEN - l + (l < IGNORE_LOW_LIGHTNESS ? 0 : (l - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);

		// Normals are currently unrotated, so we don't need to do any rotation for this
		float lightDotNormal = nx * L[0] + ny * L[1] + nz * L[2];
		if (lightDotNormal > 0) {
			lightDotNormal /= sqrt(nx * nx + ny * ny + nz * nz);
			l += (int) (lightDotNormal * color1Adjust);
		}

		int maxBrightness = 55;
		if (!legacyGreyColors)
			maxBrightness = MAX_BRIGHTNESS_LOOKUP_TABLE[s];

		// Clamp brightness as detailed above
		l = min(l, maxBrightness);

		return h << 10 | s << 7 | l;
	}

	public static void computeWorldUvsInline(
		float[] uv,
		float x1, float y1, float z1,
		float x2, float y2, float z2,
		float x3, float y3, float z3
	) {
		// N = normalize(uvw[0])
		float nx = uv[0];
		float ny = uv[1];
		float nz = uv[2];

		float invNLen = 1.0f / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		nx *= invNLen;
		ny *= invNLen;
		nz *= invNLen;

		// C1 = (0,0,1) x N
		float c1x = -ny;
		float c1y = nx;
		float c1z = 0f;

		// C2 = (0,1,0) x N
		float c2x = nz;
		float c2y = 0f;
		float c2z = -nx;

		// Choose larger
		float tx, ty, tz;
		if ((c1x * c1x + c1y * c1y) > (c2x * c2x + c2z * c2z)) {
			tx = c1x;
			ty = c1y;
			tz = c1z;
		} else {
			tx = c2x;
			ty = c2y;
			tz = c2z;
		}

		// Normalize T
		float invTLen = 1.0f / (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
		tx *= invTLen;
		ty *= invTLen;
		tz *= invTLen;

		// B = N x T
		float bx = ny * tz - nz * ty;
		float by = nz * tx - nx * tz;
		float bz = nx * ty - ny * tx;

		// scale = 1 / |uvw[0]|
		float scale = invNLen / 128.0f;

		// Vertex 1
		uv[0] = (tx * x1 + ty * y1 + tz * z1) * scale;
		uv[1] = (bx * x1 + by * y1 + bz * z1) * scale;

		// Vertex 2
		uv[4] = (tx * x2 + ty * y2 + tz * z2) * scale;
		uv[5] = (bx * x2 + by * y2 + bz * z2) * scale;

		// Vertex 3
		uv[8] = (tx * x3 + ty * y3 + tz * z3) * scale;
		uv[9] = (bx * x3 + by * y3 + bz * z3) * scale;

		// Z unused
		uv[2] = 0f;
		uv[6] = 0f;
		uv[10] = 0f;
	}
}
