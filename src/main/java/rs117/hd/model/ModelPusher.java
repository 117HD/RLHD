package rs117.hd.model;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.kit.*;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.LinkBrowser;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.WaterType;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.UvType;
import rs117.hd.overlays.FrameTimer;
import rs117.hd.overlays.Timer;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.TextureManager;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.model_overrides.InheritTileColorType;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.PopupUtils;

import static rs117.hd.HdPlugin.MAX_FACE_COUNT;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;

/**
 * Pushes models
 */
@Singleton
@Slf4j
public class ModelPusher {
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPlugin plugin;

	@Inject
	private HdPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ModelHasher modelHasher;

	@Inject
	private FrameTimer frameTimer;

	public static final int DATUM_PER_FACE = 12;
	public static final int MAX_MATERIAL_COUNT = (1 << 12) - 1;

	private static final int[] ZEROED_INTS = new int[12];

	private ModelCache modelCache;

	public void startUp() {
		if (Material.values().length - 1 >= MAX_MATERIAL_COUNT) {
			throw new IllegalStateException(
				"Too many materials (" + Material.values().length + ") to fit into packed material data.");
		}

		if (config.modelCaching() && !plugin.useLowMemoryMode) {
			final int size = config.modelCacheSizeMiB();
			try {
				modelCache = new ModelCache(size, () -> {
					shutDown();
					plugin.stopPlugin();
				});
			} catch (Throwable err) {
				log.error("Error while initializing model cache. Stopping the plugin...", err);

				if (err instanceof OutOfMemoryError) {
					PopupUtils.displayPopupMessage(client, "117 HD Error",
						"117 HD ran out of memory while trying to allocate the model cache.<br><br>" +
						(
							HDUtils.is32Bit() ?
								(
									"You are currently using 32-bit RuneLite, which heavily restricts<br>" +
									"the amount of memory RuneLite is allowed to use.<br>" +
									"Please install the 64-bit launcher from " +
									"<a href=\"" + HdPlugin.RUNELITE_URL + "\">RuneLite's website</a> and try again.<br>"
								) : (
								(
									size <= 512 ? "" :
										"Your cache size of " + size + " MiB is " + (
											size >= 4000 ?
												"very large. We would recommend reducing it.<br>" :
												"bigger than the default size. Try reducing it.<br>"
										)
								) +
								"Normally, a cache size above 512 MiB is unnecessary, and the game should<br>" +
								"run acceptably even at 128 MiB. If you have to reduce the size by a lot,<br>" +
								"you may be better off disabling model caching entirely.<br>"
							)
						)
						+ "<br>"
						+ "You can also try closing some other programs on your PC to free up memory.<br>"
						+ "<br>"
						+ "If you need further assistance, please join our "
						+ "<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord</a> server, and click the <br>"
						+ "\"Open logs folder\" button below, find the file named \"client\" or \"client.log\",<br>"
						+ "then drag and drop that file into one of our support channels.",
						new String[] { "Open logs folder", "Ok, let me try that..." },
						i -> {
							if (i == 0) {
								LinkBrowser.open(RuneLite.LOGS_DIR.toString());
								return false;
							}
							return true;
						}
					);
				}

				// Allow the model pusher to be used until the plugin has cleanly shut down
				clientThread.invoke(plugin::stopPlugin);
			}
		}
	}

	public void shutDown() {
		if (modelCache != null) {
			modelCache.destroy();
			modelCache = null;
		}
	}

	public void clearModelCache() {
		if (modelCache != null) {
			modelCache.clear();
		}
	}

	/**
	 * Pushes model data to staging buffers in the provided {@link SceneContext}, and writes the pushed number of
	 * vertices and UVs to {@link SceneContext#modelPusherResults}.
	 *
	 * @param sceneContext   object for the scene to push model data for
	 * @param tile           that the model is associated with, if any
	 * @param uuid           of the model
	 * @param model          to push data from
	 * @param modelOverride  the active model override
	 * @param objectType     of the specified model. Used for TzHaar recolor
	 * @param preOrientation which the vertices have already been rotated by
	 * @param shouldCache    whether the model should be cached for future reuse, if enabled
	 */
	public void pushModel(
		SceneContext sceneContext,
		@Nullable Tile tile,
		int uuid,
		Model model,
		ModelOverride modelOverride,
		ObjectType objectType,
		int preOrientation,
		boolean shouldCache
	) {
		if (modelCache == null)
			shouldCache = false;

		final int faceCount = Math.min(model.getFaceCount(), MAX_FACE_COUNT);
		final int bufferSize = faceCount * DATUM_PER_FACE;
		int texturedFaceCount = 0;

		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();
		boolean isVanillaTextured = faceTextures != null;
		boolean isVanillaUVMapped =
			isVanillaTextured && // Vanilla UV mapped models don't always have sensible UVs for untextured faces
			model.getTexIndices1() != null &&
			model.getTexIndices2() != null &&
			model.getTexIndices3() != null &&
			model.getTextureFaces() != null;
		Material baseMaterial = modelOverride.baseMaterial;
		Material textureMaterial = modelOverride.textureMaterial;
		if (!plugin.configModelTextures && !modelOverride.forceMaterialChanges) {
			if (baseMaterial.hasTexture)
				baseMaterial = Material.NONE;
			if (textureMaterial.hasTexture)
				textureMaterial = Material.NONE;
		}
		boolean skipUVs =
			!isVanillaTextured &&
			packMaterialData(baseMaterial, -1, modelOverride, UvType.GEOMETRY, false) == 0;

		// ensure capacity upfront
		sceneContext.stagingBufferVertices.ensureCapacity(bufferSize);
		sceneContext.stagingBufferNormals.ensureCapacity(bufferSize);
		if (!skipUVs)
			sceneContext.stagingBufferUvs.ensureCapacity(bufferSize);

		boolean foundCachedVertexData = false;
		boolean foundCachedNormalData = false;
		boolean foundCachedUvData = skipUVs;
		long vertexHash = 0;
		long normalHash = 0;
		long uvHash = 0;

		if (shouldCache) {
			assert client.isClientThread() : "Model caching isn't thread-safe";

			vertexHash = modelHasher.vertexHash;
			IntBuffer vertexData = this.modelCache.getIntBuffer(vertexHash);
			foundCachedVertexData = vertexData != null && vertexData.remaining() == bufferSize;
			if (foundCachedVertexData) {
				sceneContext.stagingBufferVertices.put(vertexData);
				vertexData.rewind();
			}

			normalHash = modelHasher.calculateNormalCacheHash();
			FloatBuffer normalData = this.modelCache.getFloatBuffer(normalHash);
			foundCachedNormalData = normalData != null && normalData.remaining() == bufferSize;
			if (foundCachedNormalData) {
				sceneContext.stagingBufferNormals.put(normalData);
				normalData.rewind();
			}

			if (!foundCachedUvData) {
				uvHash = modelHasher.calculateUvCacheHash(preOrientation, modelOverride);
				FloatBuffer uvData = this.modelCache.getFloatBuffer(uvHash);
				foundCachedUvData = uvData != null && uvData.remaining() == bufferSize;
				if (foundCachedUvData) {
					texturedFaceCount = faceCount;
					sceneContext.stagingBufferUvs.put(uvData);
					uvData.rewind();
				}
			}

			if (foundCachedVertexData && foundCachedNormalData && foundCachedUvData) {
				sceneContext.modelPusherResults[0] = faceCount;
				sceneContext.modelPusherResults[1] = texturedFaceCount;
				return;
			}
		}

		IntBuffer fullVertexData = null;
		FloatBuffer fullNormalData = null;
		FloatBuffer fullUvData = null;

		boolean shouldCacheVertexData = false;
		boolean shouldCacheNormalData = false;
		boolean shouldCacheUvData = false;
		if (shouldCache) {
			shouldCacheVertexData = !foundCachedVertexData;
			shouldCacheNormalData = !foundCachedNormalData;
			shouldCacheUvData = !foundCachedUvData;

			if (shouldCacheVertexData) {
				fullVertexData = this.modelCache.reserveIntBuffer(vertexHash, bufferSize);
				if (fullVertexData == null) {
					log.error("failed to reserve vertex buffer");
					shouldCacheVertexData = false;
				}
			}

			if (shouldCacheNormalData) {
				fullNormalData = this.modelCache.reserveFloatBuffer(normalHash, bufferSize);
				if (fullNormalData == null) {
					log.error("failed to reserve normal buffer");
					shouldCacheNormalData = false;
				}
			}

			if (shouldCacheUvData) {
				fullUvData = this.modelCache.reserveFloatBuffer(uvHash, bufferSize);
				if (fullUvData == null) {
					log.error("failed to reserve uv buffer");
					shouldCacheUvData = false;
				}
			}
		}

		if (!foundCachedVertexData) {
			if (plugin.enableDetailedTimers)
				frameTimer.begin(Timer.MODEL_PUSHING_VERTEX);

			modelOverride.applyRotation(model);
			for (int face = 0; face < faceCount; face++) {
				int[] data = getFaceVertices(sceneContext, tile, uuid, model, modelOverride, objectType, face);
				sceneContext.stagingBufferVertices.put(data);
				if (shouldCacheVertexData)
					fullVertexData.put(data);
			}
			modelOverride.revertRotation(model);

			if (plugin.enableDetailedTimers)
				frameTimer.end(Timer.MODEL_PUSHING_VERTEX);
		}

		if (!foundCachedNormalData) {
			if (plugin.enableDetailedTimers)
				frameTimer.begin(Timer.MODEL_PUSHING_NORMAL);

			for (int face = 0; face < faceCount; face++) {
				getNormalDataForFace(sceneContext, model, modelOverride, face);
				sceneContext.stagingBufferNormals.put(sceneContext.modelFaceNormals);
				if (shouldCacheNormalData)
					fullNormalData.put(sceneContext.modelFaceNormals);
			}

			if (plugin.enableDetailedTimers)
				frameTimer.end(Timer.MODEL_PUSHING_NORMAL);
		}

		if (!foundCachedUvData) {
			if (plugin.enableDetailedTimers)
				frameTimer.begin(Timer.MODEL_PUSHING_UV);

			for (int face = 0; face < faceCount; face++) {
				UvType uvType = UvType.GEOMETRY;
				Material material = baseMaterial;

				short textureId = isVanillaTextured ? faceTextures[face] : -1;
				if (textureId != -1) {
					uvType = UvType.VANILLA;
					material = textureMaterial;
					if (material == Material.NONE)
						material = Material.fromVanillaTexture(textureId);
				}

				ModelOverride materialOverride = modelOverride;
				if (modelOverride.materialOverrides != null) {
					var override = modelOverride.materialOverrides.get(material);
					if (override != null) {
						materialOverride = override;
						material = materialOverride.textureMaterial;
					}
				}

				if (material != Material.NONE) {
					uvType = materialOverride.uvType;
					if (uvType == UvType.VANILLA || (textureId != -1 && materialOverride.retainVanillaUvs))
						uvType = isVanillaUVMapped && textureFaces[face] != -1 ? UvType.VANILLA : UvType.GEOMETRY;
				}

				int materialData = packMaterialData(material, textureId, materialOverride, uvType, false);

				final float[] uvData = sceneContext.modelFaceNormals;
				if (materialData == 0) {
					Arrays.fill(uvData, 0);
				} else {
					materialOverride.fillUvsForFace(uvData, model, preOrientation, uvType, face);
					uvData[3] = uvData[7] = uvData[11] = materialData;
				}

				sceneContext.stagingBufferUvs.put(uvData);
				if (shouldCacheUvData)
					fullUvData.put(uvData);

				++texturedFaceCount;
			}

			if (plugin.enableDetailedTimers)
				frameTimer.end(Timer.MODEL_PUSHING_UV);
		}

		if (shouldCacheVertexData)
			fullVertexData.flip();
		if (shouldCacheNormalData)
			fullNormalData.flip();
		if (shouldCacheUvData)
			fullUvData.flip();

		sceneContext.modelPusherResults[0] = faceCount;
		sceneContext.modelPusherResults[1] = texturedFaceCount;
	}

	private void getNormalDataForFace(SceneContext sceneContext, Model model, @NonNull ModelOverride modelOverride, int face) {
		assert SceneUploader.packTerrainData(false, 0, WaterType.NONE, 0) == 0;
		if (modelOverride.flatNormals || !plugin.configPreserveVanillaNormals && model.getFaceColors3()[face] == -1) {
			Arrays.fill(sceneContext.modelFaceNormals, 0);
			return;
		}

		final int triA = model.getFaceIndices1()[face];
		final int triB = model.getFaceIndices2()[face];
		final int triC = model.getFaceIndices3()[face];
		final int[] xVertexNormals = model.getVertexNormalsX();
		final int[] yVertexNormals = model.getVertexNormalsY();
		final int[] zVertexNormals = model.getVertexNormalsZ();

		if (xVertexNormals == null || yVertexNormals == null || zVertexNormals == null) {
			Arrays.fill(sceneContext.modelFaceNormals, 0);
			return;
		}

		float terrainData = 0x800000; // Force undo vanilla shading in compute to not use flat normals
		sceneContext.modelFaceNormals[0] = xVertexNormals[triA];
		sceneContext.modelFaceNormals[1] = yVertexNormals[triA];
		sceneContext.modelFaceNormals[2] = zVertexNormals[triA];
		sceneContext.modelFaceNormals[3] = terrainData;
		sceneContext.modelFaceNormals[4] = xVertexNormals[triB];
		sceneContext.modelFaceNormals[5] = yVertexNormals[triB];
		sceneContext.modelFaceNormals[6] = zVertexNormals[triB];
		sceneContext.modelFaceNormals[7] = terrainData;
		sceneContext.modelFaceNormals[8] = xVertexNormals[triC];
		sceneContext.modelFaceNormals[9] = yVertexNormals[triC];
		sceneContext.modelFaceNormals[10] = zVertexNormals[triC];
		sceneContext.modelFaceNormals[11] = terrainData;
	}

	public int packMaterialData(
		@Nonnull Material material,
		int vanillaTexture,
		@Nonnull ModelOverride modelOverride,
		UvType uvType,
		boolean isOverlay
	) {
		// This needs to return zero by default, since we often fall back to writing all zeroes to UVs
		int materialIndex = textureManager.getMaterialIndex(material, vanillaTexture);
		assert materialIndex <= MAX_MATERIAL_COUNT;
		int materialData =
			(materialIndex & MAX_MATERIAL_COUNT) << 12
			| ((int) (modelOverride.shadowOpacityThreshold * 0x3F) & 0x3F) << 6
			| (!modelOverride.receiveShadows ? 1 : 0) << 5
			| (modelOverride.upwardsNormals ? 1 : 0) << 4
			| (modelOverride.flatNormals ? 1 : 0) << 3
			| (uvType.worldUvs ? 1 : 0) << 2
			| (uvType == UvType.VANILLA ? 1 : 0) << 1
			| (isOverlay ? 1 : 0);
		assert (materialData & ~0xFFFFFF) == 0 : "Only the lower 24 bits are usable, since we pass this into shaders as a float";
		return materialData;
	}

	private boolean isBakedGroundShading(Model model, int face) {
		final byte[] faceTransparencies = model.getFaceTransparencies();
		if (faceTransparencies == null || (faceTransparencies[face] & 0xFF) <= 100)
			return false;

		final short[] faceTextures = model.getFaceTextures();
		if (faceTextures != null && faceTextures[face] != -1)
			return false;

		final float[] yVertices = model.getVerticesY();
		float heightA = yVertices[model.getFaceIndices1()[face]];
		if (heightA < -8)
			return false;

		float heightB = yVertices[model.getFaceIndices2()[face]];
		float heightC = yVertices[model.getFaceIndices3()[face]];
		return heightA == heightB && heightA == heightC;
	}

	@SuppressWarnings({ "ReassignedVariable" })
	private int[] getFaceVertices(
		SceneContext sceneContext,
		Tile tile,
		int uuid,
		Model model,
		@NonNull ModelOverride modelOverride,
		ObjectType objectType,
		int face
	) {
		if (model.getFaceColors3()[face] == -2)
			return ZEROED_INTS; // Hide the face

		// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
		if (plugin.configHideFakeShadows && isBakedGroundShading(model, face)) {
			if (modelOverride.hideVanillaShadows)
				return ZEROED_INTS; // Hide the face

			if (ModelHash.getUuidType(uuid) == ModelHash.TYPE_PLAYER) {
				int index = ModelHash.getUuidId(uuid);
				Player[] players = client.getCachedPlayers();
				if (index >= 0 && index < players.length) {
					Player player = players[index];
					if (player != null && player.getPlayerComposition().getEquipmentId(KitType.WEAPON) == ItemID.MAGIC_CARPET)
						return ZEROED_INTS; // Hide the face
				}
			}
		}

		int color1 = model.getFaceColors1()[face];
		int color2 = model.getFaceColors2()[face];
		int color3 = model.getFaceColors3()[face];
		final int triA = model.getFaceIndices1()[face];
		final int triB = model.getFaceIndices2()[face];
		final int triC = model.getFaceIndices3()[face];
		final float[] xVertices = model.getVerticesX();
		final float[] yVertices = model.getVerticesY();
		final float[] zVertices = model.getVerticesZ();
		final short[] faceTextures = model.getFaceTextures();
		final byte[] faceTransparencies = model.getFaceTransparencies();
		final byte[] facePriorities = model.getFaceRenderPriorities();
		boolean isTextured = faceTextures != null && faceTextures[face] != -1;

		if (color3 == -1)
			color2 = color3 = color1;

		int packedAlphaPriorityFlags = 0;
		if (faceTransparencies != null && !isTextured)
			packedAlphaPriorityFlags |= (faceTransparencies[face] & 0xFF) << 24;
		if (facePriorities != null)
			packedAlphaPriorityFlags |= (facePriorities[face] & 0xF) << 16;

		if (isTextured) {
			// Without overriding the color for textured faces, vanilla shading remains pretty noticeable even after
			// the approximate reversal above. Ardougne rooftops is a good example, where vanilla shading results in a
			// weird-looking tint. The brightness clamp afterward is required to reduce the over-exposure introduced.
			color1 = color2 = color3 = 90;

			// Let the shader know vanilla shading reversal should be skipped for this face
			packedAlphaPriorityFlags |= 1 << 20;
		} else {
			final int overrideAmount = model.getOverrideAmount() & 0xFF;
			if (overrideAmount > 0) {
				// HSL override is not applied to flat shade faces or to textured faces
				final byte overrideHue = model.getOverrideHue();
				final byte overrideSat = model.getOverrideSaturation();
				final byte overrideLum = model.getOverrideLuminance();

				if (overrideHue != -1) {
					color1 += overrideAmount * (overrideHue - (color1 >> 10 & 0x3F)) >> 7 << 10;
					color2 += overrideAmount * (overrideHue - (color2 >> 10 & 0x3F)) >> 7 << 10;
					color3 += overrideAmount * (overrideHue - (color3 >> 10 & 0x3F)) >> 7 << 10;
				}

				if (overrideSat != -1) {
					color1 += overrideAmount * (overrideSat - (color1 >> 7 & 7)) >> 7 << 7;
					color2 += overrideAmount * (overrideSat - (color2 >> 7 & 7)) >> 7 << 7;
					color3 += overrideAmount * (overrideSat - (color3 >> 7 & 7)) >> 7 << 7;
				}

				if (overrideLum != -1) {
					color1 += overrideAmount * (overrideLum - (color1 & 0x7F)) >> 7;
					color2 += overrideAmount * (overrideLum - (color2 & 0x7F)) >> 7;
					color3 += overrideAmount * (overrideLum - (color3 & 0x7F)) >> 7;
				}
			}

			if (tile != null) {
				if (modelOverride.inheritTileColorType != InheritTileColorType.NONE) {
					final Scene scene = sceneContext.scene;
					SceneTileModel tileModel = tile.getSceneTileModel();
					SceneTilePaint tilePaint = tile.getSceneTilePaint();

					if (tilePaint != null || tileModel != null) {
						// No point in inheriting tilepaint color if the ground tile does not have a color, for example above a cave wall
						if (
							tilePaint != null &&
							tilePaint.getTexture() == -1 &&
							tilePaint.getRBG() != 0 &&
							tilePaint.getNeColor() != 12345678
						) {

							// Since tile colors are guaranteed to have the same hue and saturation per face,
							// we can blend without converting from HSL to RGB
							int averageColor =
								(
									tilePaint.getSwColor() +
									tilePaint.getNwColor() +
									tilePaint.getNeColor() +
									tilePaint.getSeColor()
								) / 4;

							var override = tileOverrideManager.getOverride(scene, tile);
							averageColor = override.modifyColor(averageColor);
							color1 = color2 = color3 = averageColor;

							// Let the shader know vanilla shading reversal should be skipped for this face
							packedAlphaPriorityFlags |= 1 << 20;
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
								if (color != 12345678) {
									var scenePos = tile.getSceneLocation();
									int tileX = scenePos.getX();
									int tileY = scenePos.getY();
									int tileZ = tile.getRenderLevel();
									int tileExX = tileX + SceneUploader.SCENE_OFFSET;
									int tileExY = tileY + SceneUploader.SCENE_OFFSET;
									int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);
									var override = tileOverrideManager.getOverride(scene, tile, worldPos,
										modelOverride.inheritTileColorType == InheritTileColorType.OVERLAY ?
											OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY] :
											scene.getUnderlayIds()[tileZ][tileExX][tileExY]
									);
									color = override.modifyColor(color);
									color1 = color2 = color3 = color;

									// Let the shader know vanilla shading reversal should be skipped for this face
									packedAlphaPriorityFlags |= 1 << 20;
								}
							}
						}
					}
				}

				if (plugin.configTzhaarHD && modelOverride.tzHaarRecolorType != TzHaarRecolorType.NONE) {
					int[] tzHaarRecolored = ProceduralGenerator.recolorTzHaar(
						modelOverride,
						model,
						face,
						packedAlphaPriorityFlags,
						objectType,
						color1,
						color2,
						color3
					);
					color1 = tzHaarRecolored[0];
					color2 = tzHaarRecolored[1];
					color3 = tzHaarRecolored[2];
					packedAlphaPriorityFlags = tzHaarRecolored[3];
				}
			}
		}

		color1 |= packedAlphaPriorityFlags;
		color2 |= packedAlphaPriorityFlags;
		color3 |= packedAlphaPriorityFlags;

		int[] data = sceneContext.modelFaceVertices;
		data[0] = Float.floatToIntBits(xVertices[triA]);
		data[1] = Float.floatToIntBits(yVertices[triA]);
		data[2] = Float.floatToIntBits(zVertices[triA]);
		data[3] = color1;
		data[4] = Float.floatToIntBits(xVertices[triB]);
		data[5] = Float.floatToIntBits(yVertices[triB]);
		data[6] = Float.floatToIntBits(zVertices[triB]);
		data[7] = color2;
		data[8] = Float.floatToIntBits(xVertices[triC]);
		data[9] = Float.floatToIntBits(yVertices[triC]);
		data[10] = Float.floatToIntBits(zVertices[triC]);
		data[11] = color3;
		return data;
	}
}
