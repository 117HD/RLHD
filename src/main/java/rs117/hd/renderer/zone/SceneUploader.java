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
import rs117.hd.HdPlugin;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.UvType;
import rs117.hd.scene.tile_overrides.TileOverride;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.buffer.GpuIntBuffer;

import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.tile_overrides.TileOverride.NONE;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
class SceneUploader {
	private static final int[] UP_NORMAL = { 0, -1, 0 };

	@Inject
	private HdPlugin plugin;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	@Inject
	public ProceduralGenerator proceduralGenerator;

	private int basex, basez, rid, level;

	void zoneSize(Scene scene, Zone zone, int mzx, int mzz) {
		Tile[][][] tiles = scene.getExtendedTiles();

		for (int z = 3; z >= 0; --z) {
			for (int xoff = 0; xoff < 8; ++xoff) {
				for (int zoff = 0; zoff < 8; ++zoff) {
					Tile t = tiles[z][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (t != null) {
						zoneSize(zone, t);
					}
				}
			}
		}
	}

	void uploadZone(ZoneSceneContext ctx, Zone zone, int mzx, int mzz) {
		int[][][] roofs = ctx.scene.getRoofs();
		Set<Integer> roofIds = new HashSet<>();

		var vb = zone.vboO != null ? new GpuIntBuffer(zone.vboO.vb) : null;
		var ab = zone.vboA != null ? new GpuIntBuffer(zone.vboA.vb) : null;

		for (int level = 0; level <= 3; ++level) {
			for (int xoff = 0; xoff < 8; ++xoff) {
				for (int zoff = 0; zoff < 8; ++zoff) {
					int rid = roofs[level][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (rid > 0) {
						roofIds.add(rid);
					}
				}
			}
		}

		zone.rids = new int[4][roofIds.size()];
		zone.roofStart = new int[4][roofIds.size()];
		zone.roofEnd = new int[4][roofIds.size()];

		for (int z = 0; z <= 3; ++z) {
			if (z == 0) {
				uploadZoneLevel(ctx, zone, mzx, mzz, z, false, roofIds, vb, ab);
				uploadZoneLevel(ctx, zone, mzx, mzz, z, true, roofIds, vb, ab);
				uploadZoneLevel(ctx, zone, mzx, mzz, 1, true, roofIds, vb, ab);
				uploadZoneLevel(ctx, zone, mzx, mzz, 2, true, roofIds, vb, ab);
				uploadZoneLevel(ctx, zone, mzx, mzz, 3, true, roofIds, vb, ab);
			} else {
				uploadZoneLevel(ctx, zone, mzx, mzz, z, false, roofIds, vb, ab);
			}

			if (zone.vboO != null) {
				int pos = zone.vboO.vb.position();
				zone.levelOffsets[z] = pos;
			}
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
		GpuIntBuffer ab
	) {
		int ridx = 0;

		// upload the roofs and save their positions
		for (int id : roofIds) {
			int pos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			uploadZoneLevelRoof(ctx, zone, mzx, mzz, level, id, visbelow, vb, ab);

			int endpos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			if (endpos > pos) {
				zone.rids[level][ridx] = id;
				zone.roofStart[level][ridx] = pos;
				zone.roofEnd[level][ridx] = endpos;
				++ridx;
			}
		}

		// upload everything else
		uploadZoneLevelRoof(ctx, zone, mzx, mzz, level, 0, visbelow, vb, ab);
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
		GpuIntBuffer ab
	) {
		byte[][][] settings = ctx.scene.getExtendedTileSettings();
		int[][][] roofs = ctx.scene.getRoofs();
		Tile[][][] tiles = ctx.scene.getExtendedTiles();

		this.level = level;
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
						uploadZoneTile(ctx, zone, t, vb, ab);
					}
				}
			}
		}
	}

	private void zoneSize(Zone z, Tile t) {
		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null) {
			z.sizeO += 2;
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null) {
			z.sizeO += model.getFaceX().length;
		}

		WallObject wallObject = t.getWallObject();
		if (wallObject != null) {
			zoneRenderableSize(z, wallObject.getRenderable1());
			zoneRenderableSize(z, wallObject.getRenderable2());
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null) {
			zoneRenderableSize(z, decorativeObject.getRenderable());
			zoneRenderableSize(z, decorativeObject.getRenderable2());
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null) {
			zoneRenderableSize(z, groundObject.getRenderable());
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null) {
				continue;
			}

			if (!gameObject.getSceneMinLocation().equals(t.getSceneLocation())) {
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			zoneRenderableSize(z, renderable);
		}

		Tile bridge = t.getBridge();
		if (bridge != null) {
			zoneSize(z, bridge);
		}
	}

	private int uploadZoneTile(ZoneSceneContext ctx, Zone zone, Tile t, GpuIntBuffer vertexBuffer, GpuIntBuffer ab) {
		int len = 0;

		var tilePoint = t.getSceneLocation();
		int tileExX = tilePoint.getX() + ctx.sceneOffset;
		int tileExY = tilePoint.getY() + ctx.sceneOffset;
		int tileZ = t.getRenderLevel();
		int[] worldPos = ctx.sceneToWorld(tilePoint.getX(), tilePoint.getY(), t.getPlane());

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null) {
			len = upload(
				ctx,
				worldPos,
				t,
				paint,
				tileExX, tileExY, tileZ,
				vertexBuffer,
				tilePoint.getX() * 128 - basex, tilePoint.getY() * 128 - basez
			);
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null)
			len += upload(ctx, worldPos, t, model, tileExX, tileExY, tileZ, basex, basez, vertexBuffer);

		WallObject wallObject = t.getWallObject();
		if (wallObject != null) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_WALL_OBJECT, wallObject.getId());
			Renderable renderable1 = wallObject.getRenderable1();
			uploadZoneRenderable(
				ctx,
				renderable1,
				zone,
				uuid,
				worldPos,
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
				ab
			);

			Renderable renderable2 = wallObject.getRenderable2();
			uploadZoneRenderable(
				ctx,
				renderable2,
				zone,
				uuid,
				worldPos,
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
				ab
			);
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null) {
			int uuid = ModelHash.packUuid(ModelHash.TYPE_DECORATIVE_OBJECT, decorativeObject.getId());
			int preOrientation = HDUtils.getModelPreOrientation(decorativeObject.getConfig());
			Renderable renderable = decorativeObject.getRenderable();
			uploadZoneRenderable(
				ctx,
				renderable,
				zone,
				uuid,
				worldPos,
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
				ab
			);

			Renderable renderable2 = decorativeObject.getRenderable2();
			uploadZoneRenderable(
				ctx,
				renderable2,
				zone,
				uuid,
				worldPos,
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
				ab
			);
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null) {
			Renderable renderable = groundObject.getRenderable();
			uploadZoneRenderable(
				ctx,
				renderable, zone,
				ModelHash.packUuid(ModelHash.TYPE_GROUND_OBJECT, groundObject.getId()),
				worldPos,
				HDUtils.getModelPreOrientation(groundObject.getConfig()),
				0,
				groundObject.getX(), groundObject.getZ(), groundObject.getY(), -1,
				-1,
				-1, -1,
				groundObject.getId(),
				vertexBuffer,
				ab
			);
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null) {
				continue;
			}

			Point min = gameObject.getSceneMinLocation(), max = gameObject.getSceneMaxLocation();

			if (!min.equals(t.getSceneLocation())) {
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			uploadZoneRenderable(
				ctx,
				renderable, zone,
				ModelHash.packUuid(ModelHash.TYPE_GAME_OBJECT, gameObject.getId()),
				worldPos,
				HDUtils.getModelPreOrientation(gameObject.getConfig()),
				gameObject.getModelOrientation(),
				gameObject.getX(), gameObject.getZ(), gameObject.getY(), min.getX(),
				min.getY(),
				max.getX(), max.getY(),
				gameObject.getId(),
				vertexBuffer,
				ab
			);
		}

		Tile bridge = t.getBridge();
		if (bridge != null) {
			len += uploadZoneTile(ctx, zone, bridge, vertexBuffer, ab);
		}

		return len;
	}

	private void zoneRenderableSize(Zone z, Renderable r) {
		Model m = null;
		if (r instanceof Model) {
			m = (Model) r;
		} else if (r instanceof DynamicObject) {
			m = ((DynamicObject) r).getModelZbuf();
		}
		if (m == null) {
			return;
		}

		int faceCount = m.getFaceCount();

		byte[] transparencies = m.getFaceTransparencies();
		short[] faceTextures = m.getFaceTextures();

		if (transparencies != null || faceTextures != null) {
			for (int face = 0; face < faceCount; ++face) {
				boolean alpha =
					transparencies != null && transparencies[face] != 0 ||
					faceTextures != null && Material.hasVanillaTransparency(faceTextures[face]);
				if (alpha) {
					z.sizeA++;
				} else {
					z.sizeO++;
				}
			}
			return;
		}

		z.sizeO += faceCount;
	}

	private void uploadZoneRenderable(
		ZoneSceneContext ctx,
		Renderable r,
		Zone zone,
		int uuid,
		int[] worldPos,
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
		GpuIntBuffer vertexBuffer,
		GpuIntBuffer ab
	) {
		ModelOverride modelOverride = modelOverrideManager.getOverride(uuid, worldPos);

		int pos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		Model model = null;
		if (r instanceof Model) {
			model = (Model) r;
			uploadStaticModel(model, modelOverride, preOrientation, orient, x - basex, y, z - basez, vertexBuffer, ab);
		} else if (r instanceof DynamicObject) {
			model = ((DynamicObject) r).getModelZbuf();
			if (model != null) {
				uploadStaticModel(model, modelOverride, preOrientation, orient, x - basex, y, z - basez, vertexBuffer, ab);
			}
		}
		int endpos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		if (endpos > pos) {
			assert model != null;
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
			zone.addAlphaModel(
				zone.glVaoA, model, pos, endpos,
				x - basex, y, z - basez,
				lx, lz, ux, uz,
				rid, level, id
			);
		}
	}

	@SuppressWarnings({ "UnnecessaryLocalVariable" })
	private int upload(
		ZoneSceneContext ctx,
		int[] worldPos,
		Tile tile,
		SceneTilePaint paint,
		int tileExX, int tileExY, int tileZ,
		GpuIntBuffer vertexBuffer,
		int lx,
		int lz
	) {
		final int[][][] tileHeights = ctx.scene.getTileHeights();
		int swHeight = tileHeights[tileZ][tileExX][tileExY];
		int seHeight = tileHeights[tileZ][tileExX + 1][tileExY];
		int neHeight = tileHeights[tileZ][tileExX + 1][tileExY + 1];
		int nwHeight = tileHeights[tileZ][tileExX][tileExY + 1];

		int swColor = paint.getSwColor();
		int seColor = paint.getSeColor();
		int neColor = paint.getNeColor();
		int nwColor = paint.getNwColor();
		int textureId = paint.getTexture();

		if (neColor == HIDDEN_HSL) {
			return 0;
		}

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

		int[] vertexKeys = ProceduralGenerator.tileVertexKeys(ctx, tile);
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

		TileOverride override = tileOverrideManager.getOverride(ctx, tile, worldPos);
		WaterType waterType = proceduralGenerator.seasonalWaterType(override, paint.getTexture());

		if (waterType == WaterType.NONE) {
			if (textureId != -1) {
				var material = materialManager.fromVanillaTexture(textureId);
				// Disable tile overrides for newly introduced vanilla textures
				if (material.isFallbackVanillaMaterial)
					override = NONE;
				swMaterial = seMaterial = neMaterial = nwMaterial = material;
			}

			swNormals = ctx.vertexTerrainNormals.getOrDefault(swVertexKey, swNormals);
			seNormals = ctx.vertexTerrainNormals.getOrDefault(seVertexKey, seNormals);
			neNormals = ctx.vertexTerrainNormals.getOrDefault(neVertexKey, neNormals);
			nwNormals = ctx.vertexTerrainNormals.getOrDefault(nwVertexKey, nwNormals);

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
		} else {
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
		}

		if (ctx.vertexIsOverlay.containsKey(neVertexKey) && ctx.vertexIsUnderlay.containsKey(neVertexKey))
			neVertexIsOverlay = true;
		if (ctx.vertexIsOverlay.containsKey(nwVertexKey) && ctx.vertexIsUnderlay.containsKey(nwVertexKey))
			nwVertexIsOverlay = true;
		if (ctx.vertexIsOverlay.containsKey(seVertexKey) && ctx.vertexIsUnderlay.containsKey(seVertexKey))
			seVertexIsOverlay = true;
		if (ctx.vertexIsOverlay.containsKey(swVertexKey) && ctx.vertexIsUnderlay.containsKey(swVertexKey))
			swVertexIsOverlay = true;


		int terrainData = HDUtils.packTerrainData(true, 0, waterType, tileZ);

		int swMaterialData = swMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, swVertexIsOverlay);
		int seMaterialData = seMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, seVertexIsOverlay);
		int nwMaterialData = nwMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, nwVertexIsOverlay);
		int neMaterialData = neMaterial.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, neVertexIsOverlay);

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

		vertexBuffer.putVertex(
			lx2, neHeight, lz2, neColor,
			uvx, uvy, 0, neMaterialData,
			neNormals[0], neNormals[2], neNormals[1], terrainData
		);

		vertexBuffer.putVertex(
			lx3, nwHeight, lz3, nwColor,
			uvx - uvcos, uvy - uvsin, 0, nwMaterialData,
			nwNormals[0], nwNormals[2], nwNormals[1], terrainData
		);

		vertexBuffer.putVertex(
			lx1, seHeight, lz1, seColor,
			uvx + uvsin, uvy - uvcos, 0, seMaterialData,
			seNormals[0], seNormals[2], seNormals[1], terrainData
		);

		vertexBuffer.putVertex(
			lx0, swHeight, lz0, swColor,
			uvx - uvcos + uvsin, uvy - uvsin - uvcos, 0, swMaterialData,
			swNormals[0], swNormals[2], swNormals[1], terrainData
		);

		vertexBuffer.putVertex(
			lx1, seHeight, lz1, seColor,
			uvx + uvsin, uvy - uvcos, 0, seMaterialData,
			seNormals[0], seNormals[2], seNormals[1], terrainData
		);

		vertexBuffer.putVertex(
			lx3, nwHeight, lz3, nwColor,
			uvx - uvcos, uvy - uvsin, 0, nwMaterialData,
			nwNormals[0], nwNormals[2], nwNormals[1], terrainData
		);

		return 6;
	}

	private int upload(
		ZoneSceneContext ctx,
		int[] worldPos,
		Tile tile,
		SceneTileModel model,
		int tileExX, int tileExY, int tileZ,
		int basex, int basez,
		GpuIntBuffer vertexBuffer
	) {
		final int[] faceX = model.getFaceX();
		final int[] faceY = model.getFaceY();
		final int[] faceZ = model.getFaceZ();

		final int[] vertexX = model.getVertexX();
		final int[] vertexY = model.getVertexY();
		final int[] vertexZ = model.getVertexZ();

		final int[] triangleColorA = model.getTriangleColorA();
		final int[] triangleColorB = model.getTriangleColorB();
		final int[] triangleColorC = model.getTriangleColorC();

		final int[] triangleTextures = model.getTriangleTextureId();

		final int faceCount = faceX.length;

		int overlayId = OVERLAY_FLAG | ctx.scene.getOverlayIds()[tileZ][tileExX][tileExY];
		int underlayId = ctx.scene.getUnderlayIds()[tileZ][tileExX][tileExY];
		var overlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, overlayId);
		var underlayOverride = tileOverrideManager.getOverride(ctx, tile, worldPos, underlayId);

		var sceneLoc = tile.getSceneLocation();
		int tileX = sceneLoc.getX();
		int tileY = sceneLoc.getY();

		int cnt = 0;
		for (int face = 0; face < faceCount; ++face) {
			final int vertex0 = faceX[face];
			final int vertex1 = faceY[face];
			final int vertex2 = faceZ[face];

			int colorA = triangleColorA[face];
			int colorB = triangleColorB[face];
			int colorC = triangleColorC[face];

			if (colorA == HIDDEN_HSL) {
				continue;
			}

			cnt += 3;

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

			int[][] localVertices = ProceduralGenerator.faceLocalVertices(tile, face);

			int[] vertexKeys = ProceduralGenerator.faceVertexKeys(tile, face);
			int vertexKeyA = vertexKeys[0];
			int vertexKeyB = vertexKeys[1];
			int vertexKeyC = vertexKeys[2];

			boolean vertexAIsOverlay = false;
			boolean vertexBIsOverlay = false;
			boolean vertexCIsOverlay = false;

			int textureId;
			Material materialA = Material.NONE;
			Material materialB = Material.NONE;
			Material materialC = Material.NONE;

			int uvOrientation = 0;
			float uvScale = 1;

			int[] normalsA = UP_NORMAL;
			int[] normalsB = UP_NORMAL;
			int[] normalsC = UP_NORMAL;

			boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
			var override = isOverlay ? overlayOverride : underlayOverride;

			textureId = triangleTextures == null ? -1 : triangleTextures[face];
			WaterType waterType = proceduralGenerator.seasonalWaterType(override, textureId);
			if (waterType == WaterType.NONE) {
				if (textureId != -1) {
					var material = materialManager.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material.isFallbackVanillaMaterial)
						override = NONE;
					materialA = materialB = materialC = material;
				}

				normalsA = ctx.vertexTerrainNormals.getOrDefault(vertexKeyA, normalsA);
				normalsB = ctx.vertexTerrainNormals.getOrDefault(vertexKeyB, normalsB);
				normalsC = ctx.vertexTerrainNormals.getOrDefault(vertexKeyC, normalsC);

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
						worldPos[0] + (localVertices[0][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (localVertices[0][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialB = groundMaterial.getRandomMaterial(
						worldPos[0] + (localVertices[1][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (localVertices[1][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
					materialC = groundMaterial.getRandomMaterial(
						worldPos[0] + (localVertices[2][0] >> LOCAL_COORD_BITS),
						worldPos[1] + (localVertices[2][1] >> LOCAL_COORD_BITS),
						worldPos[2]
					);
				}
			} else {
				// set colors for the shoreline to create a foam effect in the water shader
				colorA = colorB = colorC = 127;
				if (ctx.vertexIsWater.containsKey(vertexKeyA) && ctx.vertexIsLand.containsKey(vertexKeyA))
					colorA = 0;
				if (ctx.vertexIsWater.containsKey(vertexKeyB) && ctx.vertexIsLand.containsKey(vertexKeyB))
					colorB = 0;
				if (ctx.vertexIsWater.containsKey(vertexKeyC) && ctx.vertexIsLand.containsKey(vertexKeyC))
					colorC = 0;
			}

			if (ctx.vertexIsOverlay.containsKey(vertexKeyA) && ctx.vertexIsUnderlay.containsKey(vertexKeyA))
				vertexAIsOverlay = true;
			if (ctx.vertexIsOverlay.containsKey(vertexKeyB) && ctx.vertexIsUnderlay.containsKey(vertexKeyB))
				vertexBIsOverlay = true;
			if (ctx.vertexIsOverlay.containsKey(vertexKeyC) && ctx.vertexIsUnderlay.containsKey(vertexKeyC))
				vertexCIsOverlay = true;

			for (int i = 0; i < 3; i++)
				localVertices[i][2] -= override.heightOffset;

			int terrainData = HDUtils.packTerrainData(true, 0, waterType, tileZ);

			int materialDataA = materialA.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexAIsOverlay);
			int materialDataB = materialB.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexBIsOverlay);
			int materialDataC = materialC.packMaterialData(ModelOverride.NONE, UvType.GEOMETRY, vertexCIsOverlay);

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

			vertexBuffer.putVertex(
				lx0, ly0, lz0, colorA,
				uvAx, uvAy, 0, materialDataA,
				normalsA[0], normalsA[2], normalsA[1], terrainData
			);

			vertexBuffer.putVertex(
				lx1, ly1, lz1, colorB,
				uvBx, uvBy, 0, materialDataB,
				normalsB[0], normalsB[2], normalsB[1], terrainData
			);

			vertexBuffer.putVertex(
				lx2, ly2, lz2, colorC,
				uvCx, uvCy, 0, materialDataC,
				normalsC[0], normalsC[2], normalsC[1], terrainData
			);
		}

		return cnt;
	}

	// scene upload
	private int uploadStaticModel(
		Model model,
		ModelOverride modelOverride,
		int preOrientation, int orient, int x, int y, int z, GpuIntBuffer vertexBuffer, GpuIntBuffer ab
	) {
		final int vertexCount = model.getVerticesCount();
		final int triangleCount = model.getFaceCount();

		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		final byte[] transparencies = model.getFaceTransparencies();
		final byte[] bias = model.getFaceBias();

		int orientSin = 0;
		int orientCos = 0;
		if (orient != 0) {
			orientSin = Perspective.SINE[orient];
			orientCos = Perspective.COSINE[orient];
		}

		for (int v = 0; v < vertexCount; ++v) {
			int vx = (int) vertexX[v];
			int vy = (int) vertexY[v];
			int vz = (int) vertexZ[v];

			if (orient != 0) {
				int x0 = vx;
				vx = vz * orientSin + x0 * orientCos >> 16;
				vz = vz * orientCos - x0 * orientSin >> 16;
			}

			vx += x;
			vy += y;
			vz += z;

			modelLocalXI[v] = vx;
			modelLocalYI[v] = vy;
			modelLocalZI[v] = vz;
		}

		boolean isVanillaTextured = faceTextures != null;
		boolean isVanillaUVMapped =
			isVanillaTextured && // Vanilla UV mapped models don't always have sensible UVs for untextured faces
			model.getTextureFaces() != null;

		Material baseMaterial = modelOverride.baseMaterial;
		Material textureMaterial = modelOverride.textureMaterial;
		boolean disableTextures = !plugin.configModelTextures && !modelOverride.forceMaterialChanges;
		if (disableTextures) {
			if (baseMaterial.modifiesVanillaTexture)
				baseMaterial = Material.NONE;
			if (textureMaterial.modifiesVanillaTexture)
				textureMaterial = Material.NONE;
		}

		int len = 0;
		for (int face = 0; face < triangleCount; ++face) {
			int color1 = color1s[face];
			int color2 = color2s[face];
			int color3 = color3s[face];

			if (color3 == -1) {
				color2 = color3 = color1;
			} else if (color3 == -2) {
				continue;
			}

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

			int texA, texB, texC;

			if (isVanillaUVMapped && textureFaces[face] != -1) {
				int tface = textureFaces[face] & 0xff;
				texA = texIndices1[tface];
				texB = texIndices2[tface];
				texC = texIndices3[tface];
			} else {
				texA = triangleA;
				texB = triangleB;
				texC = triangleC;
			}

			UvType uvType = UvType.GEOMETRY;
			Material material = baseMaterial;

			int textureId = isVanillaTextured ? faceTextures[face] : -1;
			if (textureId != -1) {
				uvType = UvType.VANILLA;
				material = textureMaterial;
				if (material == Material.NONE)
					material = materialManager.fromVanillaTexture(textureId);

				color1 = color2 = color3 = 90;
			}

			ModelOverride faceOverride = modelOverride;
			if (!disableTextures) {
				if (modelOverride.materialOverrides != null) {
					var override = modelOverride.materialOverrides.get(material);
					if (override != null) {
						faceOverride = override;
						material = faceOverride.textureMaterial;
					}
				}

				// Color overrides are heavy. Only apply them if the UVs will be cached or don't need caching
				if (modelOverride.colorOverrides != null) {
					int ahsl = (transparencies == null ? 0xFF : 0xFF - (transparencies[face] & 0xFF)) << 16 | color1s[face];
					for (var override : modelOverride.colorOverrides) {
						if (override.ahslCondition.test(ahsl)) {
							faceOverride = override;
							material = faceOverride.baseMaterial;
							break;
						}
					}
				}
			}

			if (material != Material.NONE) {
				uvType = faceOverride.uvType;
				if (uvType == UvType.VANILLA || (textureId != -1 && faceOverride.retainVanillaUvs))
					uvType = isVanillaUVMapped && textureFaces[face] != -1 ? UvType.VANILLA : UvType.GEOMETRY;
			}

			int materialData = material.packMaterialData(faceOverride, uvType, false);

			int[] uvs;
			if (uvType == UvType.VANILLA) {
				uvs = new int[] {
					modelLocalXI[texA],
					modelLocalYI[texA],
					modelLocalZI[texA],
					0,
					modelLocalXI[texB],
					modelLocalYI[texB],
					modelLocalZI[texB],
					0,
					modelLocalXI[texC],
					modelLocalYI[texC],
					modelLocalZI[texC],
					0
				};
			} else {
				float[] fUvs = new float[12];
				faceOverride.fillUvsForFace(fUvs, model, preOrientation, uvType, face);
				// Assume all UVs are in the range [-100, 100], and map that to signed short
				divide(fUvs, fUvs, 100.f);
				clamp(fUvs, fUvs, -1, 1);
				multiply(fUvs, fUvs, Short.MAX_VALUE);
				uvs = round(fUvs);
			}

			int[] normals = new int[9];
			if (!modelOverride.flatNormals && (plugin.configPreserveVanillaNormals || model.getFaceColors3()[face] != -1)) {
				final int[] xVertexNormals = model.getVertexNormalsX();
				final int[] yVertexNormals = model.getVertexNormalsY();
				final int[] zVertexNormals = model.getVertexNormalsZ();
				if (xVertexNormals != null && yVertexNormals != null && zVertexNormals != null) {
					normals[0] = xVertexNormals[triangleA];
					normals[1] = yVertexNormals[triangleA];
					normals[2] = zVertexNormals[triangleA];
					normals[3] = xVertexNormals[triangleB];
					normals[4] = yVertexNormals[triangleB];
					normals[5] = zVertexNormals[triangleB];
					normals[6] = xVertexNormals[triangleC];
					normals[7] = yVertexNormals[triangleC];
					normals[8] = zVertexNormals[triangleC];
				}
			}

			boolean alpha =
				transparencies != null && transparencies[face] != 0 ||
				faceTextures != null && Material.hasVanillaTransparency(faceTextures[face]);

			int alphaBias = 0;
			alphaBias |= transparencies != null ? (transparencies[face] & 0xff) << 24 : 0;
			alphaBias |= bias != null ? (bias[face] & 0xff) << 16 : 0;
			GpuIntBuffer vb = alpha ? ab : vertexBuffer;

			vb.putVertex(
				vx1, vy1, vz1, alphaBias | color1,
				uvs[0], uvs[1], uvs[2], materialData,
				normals[0], normals[1], normals[2], 0
			);

			vb.putVertex(
				vx2, vy2, vz2, alphaBias | color2,
				uvs[4], uvs[5], uvs[6], materialData,
				normals[3], normals[4], normals[5], 0
			);

			vb.putVertex(
				vx3, vy3, vz3, alphaBias | color3,
				uvs[8], uvs[9], uvs[10], materialData,
				normals[6], normals[7], normals[8], 0
			);

			len += 3;
		}

		return len;
	}

	// temp draw
	int uploadTempModel(
		Model model,
		ModelOverride modelOverride,
		int preOrientation,
		int orientation,
		int x,
		int y,
		int z,
		IntBuffer opaqueBuffer
	) {
		final int triangleCount = model.getFaceCount();
		final int vertexCount = model.getVerticesCount();

		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();
		final int[] texIndices1 = model.getTexIndices1();
		final int[] texIndices2 = model.getTexIndices2();
		final int[] texIndices3 = model.getTexIndices3();

		final byte[] bias = model.getFaceBias();
		final byte[] transparencies = model.getFaceTransparencies();

		final byte overrideAmount = model.getOverrideAmount();
		final byte overrideHue = model.getOverrideHue();
		final byte overrideSat = model.getOverrideSaturation();
		final byte overrideLum = model.getOverrideLuminance();

		float orientSine = 0;
		float orientCosine = 0;
		if (orientation != 0) {
			orientSine = Perspective.SINE[orientation] / 65536f;
			orientCosine = Perspective.COSINE[orientation] / 65536f;
		}

		boolean isVanillaTextured = faceTextures != null;
		boolean isVanillaUVMapped =
			isVanillaTextured && // Vanilla UV mapped models don't always have sensible UVs for untextured faces
			model.getTextureFaces() != null;

		Material baseMaterial = modelOverride.baseMaterial;
		Material textureMaterial = modelOverride.textureMaterial;
		boolean disableTextures = !plugin.configModelTextures && !modelOverride.forceMaterialChanges;
		if (disableTextures) {
			if (baseMaterial.modifiesVanillaTexture)
				baseMaterial = Material.NONE;
			if (textureMaterial.modifiesVanillaTexture)
				textureMaterial = Material.NONE;
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

		int len = 0;
		for (int face = 0; face < triangleCount; ++face) {
			int color1 = color1s[face];
			int color2 = color2s[face];
			int color3 = color3s[face];

			if (color3 == -1) {
				color2 = color3 = color1;
			} else if (color3 == -2) {
				continue;
			}

			// HSL override is not applied to textured faces
			if (overrideAmount > 0 && (!isVanillaTextured || faceTextures[face] == -1)) {
				color1 = interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
				color2 = interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
				color3 = interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
			}

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

			int texA, texB, texC;

			if (isVanillaUVMapped && textureFaces[face] != -1) {
				int tface = textureFaces[face] & 0xff;
				texA = texIndices1[tface];
				texB = texIndices2[tface];
				texC = texIndices3[tface];
			} else {
				texA = triangleA;
				texB = triangleB;
				texC = triangleC;
			}

			UvType uvType = UvType.GEOMETRY;
			Material material = baseMaterial;

			int textureId = isVanillaTextured ? faceTextures[face] : -1;
			if (textureId != -1) {
				uvType = UvType.VANILLA;
				material = textureMaterial;
				if (material == Material.NONE)
					material = materialManager.fromVanillaTexture(textureId);

				color1 = color2 = color3 = 90;
			}

			ModelOverride faceOverride = modelOverride;
			if (!disableTextures) {
				if (modelOverride.materialOverrides != null) {
					var override = modelOverride.materialOverrides.get(material);
					if (override != null) {
						faceOverride = override;
						material = faceOverride.textureMaterial;
					}
				}

				// Color overrides are heavy. Only apply them if the UVs will be cached or don't need caching
				if (modelOverride.colorOverrides != null) {
					int ahsl = (transparencies == null ? 0xFF : 0xFF - (transparencies[face] & 0xFF)) << 16 | color1s[face];
					for (var override : modelOverride.colorOverrides) {
						if (override.ahslCondition.test(ahsl)) {
							faceOverride = override;
							material = faceOverride.baseMaterial;
							break;
						}
					}
				}
			}

			if (material != Material.NONE) {
				uvType = faceOverride.uvType;
				if (uvType == UvType.VANILLA || (textureId != -1 && faceOverride.retainVanillaUvs))
					uvType = isVanillaUVMapped && textureFaces[face] != -1 ? UvType.VANILLA : UvType.GEOMETRY;
			}

			int materialData = material.packMaterialData(faceOverride, uvType, false);

			int[] uvs;
			if (uvType == UvType.VANILLA) {
				uvs = new int[] {
					round(modelLocalX[texA]),
					round(modelLocalY[texA]),
					round(modelLocalZ[texA]),
					0,
					round(modelLocalX[texB]),
					round(modelLocalY[texB]),
					round(modelLocalZ[texB]),
					0,
					round(modelLocalX[texC]),
					round(modelLocalY[texC]),
					round(modelLocalZ[texC]),
					0
				};
			} else {
				float[] fUvs = new float[12];
				faceOverride.fillUvsForFace(fUvs, model, preOrientation, uvType, face);
				// Assume all UVs are in the range [-100, 100], and map that to signed short
				divide(fUvs, fUvs, 100.f);
				clamp(fUvs, fUvs, -1, 1);
				multiply(fUvs, fUvs, Short.MAX_VALUE);
				uvs = round(fUvs);
			}

			int[] normals = new int[9];
			if (!modelOverride.flatNormals && (plugin.configPreserveVanillaNormals || model.getFaceColors3()[face] != -1)) {
				final int[] xVertexNormals = model.getVertexNormalsX();
				final int[] yVertexNormals = model.getVertexNormalsY();
				final int[] zVertexNormals = model.getVertexNormalsZ();
				if (xVertexNormals != null && yVertexNormals != null && zVertexNormals != null) {
					normals[0] = xVertexNormals[triangleA];
					normals[1] = yVertexNormals[triangleA];
					normals[2] = zVertexNormals[triangleA];
					normals[3] = xVertexNormals[triangleB];
					normals[4] = yVertexNormals[triangleB];
					normals[5] = zVertexNormals[triangleB];
					normals[6] = xVertexNormals[triangleC];
					normals[7] = yVertexNormals[triangleC];
					normals[8] = zVertexNormals[triangleC];
				}
			}

			int alphaBias = 0;
			alphaBias |= bias != null ? (bias[face] & 0xff) << 16 : 0;

			GpuIntBuffer.putFloatVertex(
				opaqueBuffer,
				vx1, vy1, vz1, alphaBias | color1,
				uvs[0], uvs[1], uvs[2], materialData,
				normals[0], normals[1], normals[2], 0
			);

			GpuIntBuffer.putFloatVertex(
				opaqueBuffer,
				vx2, vy2, vz2, alphaBias | color2,
				uvs[4], uvs[5], uvs[6], materialData,
				normals[3], normals[4], normals[5], 0
			);

			GpuIntBuffer.putFloatVertex(
				opaqueBuffer,
				vx3, vy3, vz3, alphaBias | color3,
				uvs[8], uvs[9], uvs[10], materialData,
				normals[6], normals[7], normals[8], 0
			);

			len += 3;
		}

		return len;
	}

	static float[] modelLocalX;
	static float[] modelLocalY;
	static float[] modelLocalZ;

	// uploadModelScene runs on the maploader thread, so requires its own buffers
	private final static int[] modelLocalXI;
	private final static int[] modelLocalYI;
	private final static int[] modelLocalZI;

	static final int MAX_VERTEX_COUNT = 6500;

	static {
		modelLocalX = new float[MAX_VERTEX_COUNT];
		modelLocalY = new float[MAX_VERTEX_COUNT];
		modelLocalZ = new float[MAX_VERTEX_COUNT];

		modelLocalXI = new int[MAX_VERTEX_COUNT];
		modelLocalYI = new int[MAX_VERTEX_COUNT];
		modelLocalZI = new int[MAX_VERTEX_COUNT];
	}

	static int interpolateHSL(int hsl, byte hue2, byte sat2, byte lum2, byte lerp) {
		int hue = hsl >> 10 & 63;
		int sat = hsl >> 7 & 7;
		int lum = hsl & 127;
		int var9 = lerp & 255;
		if (hue2 != -1) {
			hue += var9 * (hue2 - hue) >> 7;
		}

		if (sat2 != -1) {
			sat += var9 * (sat2 - sat) >> 7;
		}

		if (lum2 != -1) {
			lum += var9 * (lum2 - lum) >> 7;
		}

		return (hue << 10 | sat << 7 | lum) & 65535;
	}
}
