package rs117.hd.model;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
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
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.Underlay;
import rs117.hd.data.materials.UvType;
import rs117.hd.scene.ModelOverrideManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.model_overrides.InheritTileColorType;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.PopupUtils;

import static rs117.hd.utils.HDUtils.dotLightDirectionModel;

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
	private ModelHasher modelHasher;

	@Inject
	private ModelOverrideManager modelOverrideManager;

	public static final int DATUM_PER_FACE = 12;
	public static final int MAX_MATERIAL_COUNT = (1 << 10) - 1;
	// subtracts the X lowest lightness levels from the formula.
	// helps keep darker colors appropriately dark
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	// multiplier applied to vertex' lightness value.
	// results in greater lightening of lighter colors
	private static final float LIGHTNESS_MULTIPLIER = 3f;
	// the minimum amount by which each color will be lightened
	private static final int BASE_LIGHTEN = 10;

	private ModelCache modelCache;

	public void startUp() {
		if (Material.values().length - 1 >= MAX_MATERIAL_COUNT) {
			throw new IllegalStateException(
				"Too many materials (" + Material.values().length + ") to fit into packed material data.");
		}

		if (config.modelCaching()) {
			final int size = config.modelCacheSizeMiB();
			try {
				modelCache = new ModelCache(size, () -> {
					shutDown();
					plugin.stopPlugin();
				});
			} catch (Throwable err) {
				log.error("Error while initializing model cache. Stopping the plugin...", err);

				if (err instanceof OutOfMemoryError) {
					String arch = System.getProperty("sun.arch.data.model", "Unknown");
					PopupUtils.displayPopupMessage(client, "117HD Error",
						"117HD ran out of memory while trying to allocate the model cache.<br><br>" +
						(arch.equals("32") ?
							(
								"You are currently using the 32-bit RuneLite launcher, which heavily restricts<br>" +
								"the amount of memory RuneLite is allowed to use.<br>" +
								"Please install the 64-bit launcher from " +
								"<a href=\"" + HdPlugin.RUNELITE_URL + "\">RuneLite's website</a> and try again.<br>"
							) : (
								(size <= 512 ? "" :
									"Your cache size of " + size + " MiB is " + (
										size >= 4096 ?
											"very large. We would recommend reducing it.<br>" :
											"bigger than the default size. Try reducing it.<br>"
									)
								) +
								"Normally, a cache size above 512 MiB is unnecessary, and the game should<br>" +
								"run acceptably even at 256 MiB. If you have to reduce the size by a lot,<br>" +
								"you may be better off disabling model caching entirely.<br>"
							)
						) +
						"<br>" +
						"You can also try closing some other programs on your PC to free up memory.<br>" +
						"<br>" +
						"If you need further assistance, please join our " +
						"<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord</a> server, and<br>" +
						"drag and drop your client log file into one of our support channels.",
						new String[]{ "Open log folder", "Ok, let me try that..." },
						i -> {
							if (i == 0)
								LinkBrowser.open(RuneLite.LOGS_DIR.toString());
						});
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
	 * @param hash           of the model
	 * @param model          to push data from
	 * @param objectType     of the specified model. Used for TzHaar recolor
	 * @param preOrientation which the vertices have already been rotated by
	 * @param shouldCache    whether the model should be cached for future reuse, if enabled
	 */
	public void pushModel(
		SceneContext sceneContext,
		@Nullable Tile tile,
		long hash,
		Model model,
		ObjectType objectType,
		int preOrientation,
		boolean shouldCache
	) {
		if (modelCache == null) {
			shouldCache = false;
		}

		final int faceCount = Math.min(model.getFaceCount(), HdPlugin.MAX_TRIANGLE);
		final int bufferSize = faceCount * DATUM_PER_FACE;
		int vertexLength = 0;
		int uvLength = 0;

		ModelOverride modelOverride = modelOverrideManager.getOverride(hash);
		boolean useMaterialOverrides = plugin.configModelTextures || modelOverride.forceOverride;
		final short[] faceTextures = model.getFaceTextures();
		final byte[] textureFaces = model.getTextureFaces();
		boolean isVanillaTextured = faceTextures != null;
		boolean isVanillaUVMapped =
			isVanillaTextured && // Vanilla UV mapped models don't always have sensible UVs for untextured faces
			model.getTexIndices1() != null &&
			model.getTexIndices2() != null &&
			model.getTexIndices3() != null &&
			model.getTextureFaces() != null;
		Material baseMaterial = Material.NONE;
		Material textureMaterial = Material.NONE;
		if (useMaterialOverrides) {
			baseMaterial = modelOverride.baseMaterial;
			textureMaterial = modelOverride.textureMaterial;
		}
		boolean skipUVs = !isVanillaTextured &&
			baseMaterial == Material.NONE &&
			packMaterialData(Material.NONE, modelOverride, UvType.GEOMETRY, false) == 0;

		// ensure capacity upfront
		sceneContext.stagingBufferVertices.ensureCapacity(bufferSize);
		sceneContext.stagingBufferNormals.ensureCapacity(bufferSize);
		if (!skipUVs) {
			sceneContext.stagingBufferUvs.ensureCapacity(bufferSize);
		}

		boolean foundCachedVertexData = false;
		boolean foundCachedNormalData = false;
		boolean foundCachedUvData = skipUVs;
		int vertexHash = 0;
		int normalHash = 0;
		int uvHash = 0;

		if (shouldCache) {
			assert client.isClientThread() : "Model caching isn't thread-safe";

			vertexHash = modelHasher.calculateVertexCacheHash();
			IntBuffer vertexData = this.modelCache.getIntBuffer(vertexHash);
			foundCachedVertexData = vertexData != null && vertexData.remaining() == bufferSize;
			if (foundCachedVertexData) {
				vertexLength = faceCount * 3;
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
					uvLength = faceCount * 3;
					sceneContext.stagingBufferUvs.put(uvData);
					uvData.rewind();
				}
			}

			if (foundCachedVertexData && foundCachedNormalData && foundCachedUvData) {
				sceneContext.modelPusherResults[0] = vertexLength;
				sceneContext.modelPusherResults[1] = uvLength;
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

		for (int face = 0; face < faceCount; face++) {
			if (!foundCachedVertexData) {
				getFaceVertices(sceneContext, tile, hash, model, modelOverride, objectType, face);
				sceneContext.stagingBufferVertices.put(sceneContext.modelFaceVertices);
				vertexLength += 3;

				if (shouldCacheVertexData) {
					fullVertexData.put(sceneContext.modelFaceVertices);
				}
			}

			if (!foundCachedNormalData) {
				getNormalDataForFace(sceneContext, model, modelOverride, face);
				sceneContext.stagingBufferNormals.put(sceneContext.modelFaceNormals);

				if (shouldCacheNormalData) {
					fullNormalData.put(sceneContext.modelFaceNormals);
				}
			}

			if (!foundCachedUvData) {
				Material material = baseMaterial;
				short textureId = isVanillaTextured ? faceTextures[face] : -1;
				if (textureId != -1) {
					material = textureMaterial;
					if (material == Material.NONE)
						material = Material.getTexture(textureId);
				}

				UvType uvType = modelOverride.uvType;
				boolean isFaceVanillaTextured = isVanillaUVMapped && textureId != -1 && textureFaces[face] != -1;
				if (uvType == UvType.VANILLA && !isFaceVanillaTextured)
					uvType = UvType.GEOMETRY;
				int materialData = packMaterialData(material, modelOverride, uvType, false);

				final float[] uvData = sceneContext.modelFaceNormals;
				if (materialData == 0) {
					Arrays.fill(uvData, 0);
				} else {
					modelOverride.fillUvsForFace(uvData, model, preOrientation, uvType, face);
					uvData[3] = uvData[7] = uvData[11] = materialData;
				}

				sceneContext.stagingBufferUvs.put(uvData);
				if (shouldCacheUvData) {
					fullUvData.put(uvData);
				}
				uvLength += 3;
			}
		}

		if (shouldCacheVertexData) {
			fullVertexData.flip();
		}

		if (shouldCacheNormalData) {
			fullNormalData.flip();
		}

		if (shouldCacheUvData) {
			fullUvData.flip();
		}

		sceneContext.modelPusherResults[0] = vertexLength;
		sceneContext.modelPusherResults[1] = uvLength;
	}

	private void getNormalDataForFace(SceneContext sceneContext, Model model, @NonNull ModelOverride modelOverride, int face) {
		int terrainData = SceneUploader.packTerrainData(false, 0, WaterType.NONE, 0);
		if (terrainData == 0 && (modelOverride.flatNormals || model.getFaceColors3()[face] == -1)) {
			Arrays.fill(sceneContext.modelFaceNormals, 0);
			return;
		}

		final int triA = model.getFaceIndices1()[face];
		final int triB = model.getFaceIndices2()[face];
		final int triC = model.getFaceIndices3()[face];
		final int[] xVertexNormals = model.getVertexNormalsX();
		final int[] yVertexNormals = model.getVertexNormalsY();
		final int[] zVertexNormals = model.getVertexNormalsZ();

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

	public int packMaterialData(Material material, @NonNull ModelOverride modelOverride, UvType uvType, boolean isOverlay) {
		// Only the lower 24 bits can be safely used due to imprecise casting to float in shaders
		return // This needs to return zero by default, since we often fall back to writing all zeroes to UVs
			(material.ordinal() & MAX_MATERIAL_COUNT) << 12
			| ((int) (modelOverride.shadowOpacityThreshold * 0x3F) & 0x3F) << 5
			| (!modelOverride.receiveShadows ? 1 : 0) << 4
			| (modelOverride.flatNormals ? 1 : 0) << 3
			| (uvType.worldUvs ? 1 : 0) << 2
			| (uvType == UvType.VANILLA ? 1 : 0) << 1
			| (isOverlay ? 1 : 0);
	}

	private boolean isBakedGroundShading(int face, int heightA, int heightB, int heightC, byte[] faceTransparencies, short[] faceTextures) {
		return
			faceTransparencies != null &&
			heightA >= -8 &&
			heightA == heightB &&
			heightA == heightC &&
			(faceTextures == null || faceTextures[face] == -1) &&
			(faceTransparencies[face] & 0xFF) > 100;
	}

	private void getFaceVertices(SceneContext sceneContext, Tile tile, long hash, Model model, @NonNull ModelOverride modelOverride, ObjectType objectType, int face) {
		final Scene scene = sceneContext.scene;
		final int triA = model.getFaceIndices1()[face];
		final int triB = model.getFaceIndices2()[face];
		final int triC = model.getFaceIndices3()[face];
		final byte[] faceTransparencies = model.getFaceTransparencies();
		final short[] faceTextures = model.getFaceTextures();
		final int[] xVertices = model.getVerticesX();
		final int[] yVertices = model.getVerticesY();
		final int[] zVertices = model.getVerticesZ();
		final int[] xVertexNormals = model.getVertexNormalsX();
		final int[] yVertexNormals = model.getVertexNormalsY();
		final int[] zVertexNormals = model.getVertexNormalsZ();
		final byte overrideAmount = model.getOverrideAmount();
		final byte overrideHue = model.getOverrideHue();
		final byte overrideSat = model.getOverrideSaturation();
		final byte overrideLum = model.getOverrideLuminance();

		int heightA = yVertices[triA];
		int heightB = yVertices[triB];
		int heightC = yVertices[triC];

		int color1 = model.getFaceColors1()[face];
		int color2 = model.getFaceColors2()[face];
		int color3 = model.getFaceColors3()[face];

		// Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
		if (plugin.configHideFakeShadows && isBakedGroundShading(face, heightA, heightB, heightC, faceTransparencies, faceTextures)) {
			boolean removeBakedLighting = modelOverride.removeBakedLighting;

			if (ModelHash.getType(hash) == ModelHash.TYPE_PLAYER) {
				int index = ModelHash.getIdOrIndex(hash);
				Player[] players = client.getCachedPlayers();
				Player player = index >= 0 && index < players.length ? players[index] : null;
				if (player != null && player.getPlayerComposition().getEquipmentId(KitType.WEAPON) == ItemID.MAGIC_CARPET) {
					removeBakedLighting = true;
				}
			}

			if (removeBakedLighting)
				color3 = -2;
		}

		if (color3 == -2) {
			// Zero out vertex positions to effectively hide the face
			Arrays.fill(sceneContext.modelFaceVertices, 0);
			return;
		} else if (color3 == -1) {
			color2 = color3 = color1;
		} else if ((faceTextures == null || faceTextures[face] == -1) && overrideAmount > 0) {
			// HSL override is not applied to flat shade faces or to textured faces
			color1 = interpolateHSL(color1, overrideHue, overrideSat, overrideLum, overrideAmount);
			color2 = interpolateHSL(color2, overrideHue, overrideSat, overrideLum, overrideAmount);
			color3 = interpolateHSL(color3, overrideHue, overrideSat, overrideLum, overrideAmount);
		}

		int color1H = color1 >> 10 & 0x3F;
		int color1S = color1 >> 7 & 0x7;
		int color1L = color1 & 0x7F;
		int color2H = color2 >> 10 & 0x3F;
		int color2S = color2 >> 7 & 0x7;
		int color2L = color2 & 0x7F;
		int color3H = color3 >> 10 & 0x3F;
		int color3S = color3 >> 7 & 0x7;
		int color3L = color3 & 0x7F;

		// Approximately invert vanilla shading by brightening vertices that were likely darkened by vanilla based on
		// vertex normals. This process is error-prone, as not all models are lit by vanilla with the same light
		// direction, and some models even have baked lighting built into the model itself. In some cases, increasing
		// brightness in this way leads to overly bright colors, so we are forced to cap brightness at a relatively
		// low value for it to look acceptable in most cases.
		if (modelOverride.flatNormals) {
			float[] T = {
				xVertices[triA] - xVertices[triB],
				yVertices[triA] - yVertices[triB],
				zVertices[triA] - zVertices[triB]
			};
			float[] B = {
				xVertices[triA] - xVertices[triC],
				yVertices[triA] - yVertices[triC],
				zVertices[triA] - zVertices[triC]
			};
			float[] N = new float[3];
			N[0] = T[1] * B[2] - T[2] * B[1];
			N[1] = T[2] * B[0] - T[0] * B[2];
			N[2] = T[0] * B[1] - T[1] * B[0];
			float length = (float) Math.sqrt(N[0] * N[0] + N[1] * N[1] + N[2] * N[2]);
			if (length < HDUtils.EPSILON) {
				N[0] = N[1] = N[2] = 0;
			} else {
				N[0] /= length;
				N[1] /= length;
				N[2] /= length;
			}

			float[] L = HDUtils.LIGHT_DIR_MODEL;
			float lightDotNormal = Math.max(0, N[0] * L[0] + N[1] * L[1] + N[2] * L[2]);

			int lightenA = (int) (Math.max((color1L - IGNORE_LOW_LIGHTNESS), 0) * LIGHTNESS_MULTIPLIER) + BASE_LIGHTEN;
			color1L = (int) HDUtils.lerp(color1L, lightenA, lightDotNormal);

			int lightenB = (int) (Math.max((color2L - IGNORE_LOW_LIGHTNESS), 0) * LIGHTNESS_MULTIPLIER) + BASE_LIGHTEN;
			color2L = (int) HDUtils.lerp(color2L, lightenB, lightDotNormal);

			int lightenC = (int) (Math.max((color3L - IGNORE_LOW_LIGHTNESS), 0) * LIGHTNESS_MULTIPLIER) + BASE_LIGHTEN;
			color3L = (int) HDUtils.lerp(color3L, lightenC, lightDotNormal);
		} else {
			int lightenA = (int) (Math.max((color1L - IGNORE_LOW_LIGHTNESS), 0) * LIGHTNESS_MULTIPLIER) + BASE_LIGHTEN;
			float dotA = Math.max(0, dotLightDirectionModel(
				xVertexNormals[triA],
				yVertexNormals[triA],
				zVertexNormals[triA]
			));
			color1L = (int) HDUtils.lerp(color1L, lightenA, dotA);

			int lightenB = (int) (Math.max((color2L - IGNORE_LOW_LIGHTNESS), 0) * LIGHTNESS_MULTIPLIER) + BASE_LIGHTEN;
			float dotB = Math.max(0, dotLightDirectionModel(
				xVertexNormals[triB],
				yVertexNormals[triB],
				zVertexNormals[triB]
			));
			color2L = (int) HDUtils.lerp(color2L, lightenB, dotB);

			int lightenC = (int) (Math.max((color3L - IGNORE_LOW_LIGHTNESS), 0) * LIGHTNESS_MULTIPLIER) + BASE_LIGHTEN;
			float dotC = Math.max(0, dotLightDirectionModel(
				xVertexNormals[triC],
				yVertexNormals[triC],
				zVertexNormals[triC]
			));
			color3L = (int) HDUtils.lerp(color3L, lightenC, dotC);
		}

		int maxBrightness1 = 55;
		int maxBrightness2 = 55;
		int maxBrightness3 = 55;
		if (!plugin.configLegacyGreyColors) {
			maxBrightness1 = (int) HDUtils.lerp(127, maxBrightness1, (float) Math.pow((float) color1S / 0x7, .05));
			maxBrightness2 = (int) HDUtils.lerp(127, maxBrightness2, (float) Math.pow((float) color2S / 0x7, .05));
			maxBrightness3 = (int) HDUtils.lerp(127, maxBrightness3, (float) Math.pow((float) color3S / 0x7, .05));
		}
		if (faceTextures != null && faceTextures[face] != -1) {
			// Without overriding the color for textured faces, vanilla shading remains pretty noticeable even after
			// the approximate reversal above. Ardougne rooftops is a good example, where vanilla shading results in a
			// weird-looking tint. The brightness clamp afterwards is required to reduce the over-exposure introduced.
			color1H = color2H = color3H = 0;
			color1S = color2S = color3S = 0;
			color1L = color2L = color3L = 127;
			maxBrightness1 = maxBrightness2 = maxBrightness3 = 90;
		}

		if (tile != null && modelOverride.inheritTileColorType != InheritTileColorType.NONE) {
			SceneTileModel tileModel = tile.getSceneTileModel();
			SceneTilePaint tilePaint = tile.getSceneTilePaint();

			if (tilePaint != null || tileModel != null) {
				int[] tileColorHSL;

				// No point in inheriting tilepaint color if the ground tile does not have a color, for example above a cave wall
				if (tilePaint != null && tilePaint.getTexture() == -1 && tilePaint.getRBG() != 0 && tilePaint.getNeColor() != 12345678) {
					// pull any corner color as either one should be OK
					tileColorHSL = HDUtils.colorIntToHSL(tilePaint.getNeColor());

					// average saturation and lightness
					tileColorHSL[1] = (
						tileColorHSL[1] +
						HDUtils.colorIntToHSL(tilePaint.getSeColor())[1] +
						HDUtils.colorIntToHSL(tilePaint.getNwColor())[1] +
						HDUtils.colorIntToHSL(tilePaint.getNeColor())[1]
					) / 4;

					tileColorHSL[2] = (
						tileColorHSL[2] +
						HDUtils.colorIntToHSL(tilePaint.getSeColor())[2] +
						HDUtils.colorIntToHSL(tilePaint.getNwColor())[2] +
						HDUtils.colorIntToHSL(tilePaint.getNeColor())[2]
					) / 4;

					Overlay overlay = Overlay.getOverlay(scene, tile, plugin);
					if (overlay != Overlay.NONE) {
						overlay.modifyColor(tileColorHSL);
					} else {
						Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
						underlay.modifyColor(tileColorHSL);
					}

					color1H = color2H = color3H = tileColorHSL[0];
					color1S = color2S = color3S = tileColorHSL[1];
					color1L = color2L = color3L = tileColorHSL[2];

				} else if (tileModel != null && tileModel.getTriangleTextureId() == null) {
					int faceColorIndex = -1;
					for (int i = 0; i < tileModel.getTriangleColorA().length; i++) {
						boolean isOverlayFace = ProceduralGenerator.isOverlayFace(tile, i);
						// Use underlay if the tile does not have an overlay, useful for rocks in cave corners.
						if (modelOverride.inheritTileColorType == InheritTileColorType.UNDERLAY || tileModel.getModelOverlay() == 0) {
							// pulling the color from UNDERLAY is more desirable for green grass tiles
							// OVERLAY pulls in path color which is not desirable for grass next to paths
							if (!isOverlayFace) {
								faceColorIndex = i;
								break;
							}
						} else if (modelOverride.inheritTileColorType == InheritTileColorType.OVERLAY) {
							if (isOverlayFace) {
								// OVERLAY used in dirt/path/house tile color blend better with rubbles/rocks
								faceColorIndex = i;
								break;
							}
						}
					}

					if (faceColorIndex != -1) {
						int color = tileModel.getTriangleColorA()[faceColorIndex];
						if (color != 12345678) {
							tileColorHSL = HDUtils.colorIntToHSL(color);

							Underlay underlay = Underlay.getUnderlay(scene, tile, plugin);
							underlay.modifyColor(tileColorHSL);

							color1H = color2H = color3H = tileColorHSL[0];
							color1S = color2S = color3S = tileColorHSL[1];
							color1L = color2L = color3L = tileColorHSL[2];
						}
					}
				}
			}
		}

		int packedAlphaPriority = getPackedAlphaPriority(model, face);

		if (plugin.configTzhaarHD && modelOverride.tzHaarRecolorType != TzHaarRecolorType.NONE) {
			int[][] tzHaarRecolored = ProceduralGenerator.recolorTzHaar(modelOverride, heightA, heightB, heightC, packedAlphaPriority, objectType, color1S, color1L, color2S, color2L, color3S, color3L);
			color1H = tzHaarRecolored[0][0];
			color1S = tzHaarRecolored[0][1];
			color1L = tzHaarRecolored[0][2];
			color2H = tzHaarRecolored[1][0];
			color2S = tzHaarRecolored[1][1];
			color2L = tzHaarRecolored[1][2];
			color3H = tzHaarRecolored[2][0];
			color3S = tzHaarRecolored[2][1];
			color3L = tzHaarRecolored[2][2];
			packedAlphaPriority = tzHaarRecolored[3][0];
		}

		// Clamp brightness as detailed above
		color1L = HDUtils.clamp(color1L, 0, maxBrightness1);
		color2L = HDUtils.clamp(color2L, 0, maxBrightness2);
		color3L = HDUtils.clamp(color3L, 0, maxBrightness3);

		color1 = (color1H << 3 | color1S) << 7 | color1L;
		color2 = (color2H << 3 | color2S) << 7 | color2L;
		color3 = (color3H << 3 | color3S) << 7 | color3L;

		sceneContext.modelFaceVertices[0] = xVertices[triA];
		sceneContext.modelFaceVertices[1] = yVertices[triA];
		sceneContext.modelFaceVertices[2] = zVertices[triA];
		sceneContext.modelFaceVertices[3] = packedAlphaPriority | color1;
		sceneContext.modelFaceVertices[4] = xVertices[triB];
		sceneContext.modelFaceVertices[5] = yVertices[triB];
		sceneContext.modelFaceVertices[6] = zVertices[triB];
		sceneContext.modelFaceVertices[7] = packedAlphaPriority | color2;
		sceneContext.modelFaceVertices[8] = xVertices[triC];
		sceneContext.modelFaceVertices[9] = yVertices[triC];
		sceneContext.modelFaceVertices[10] = zVertices[triC];
		sceneContext.modelFaceVertices[11] = packedAlphaPriority | color3;
	}

	private static int interpolateHSL(int hsl, byte hue2, byte sat2, byte lum2, byte lerp) {
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

	private int getPackedAlphaPriority(Model model, int face) {
		final short[] faceTextures = model.getFaceTextures();
		final byte[] faceTransparencies = model.getFaceTransparencies();
		final byte[] facePriorities = model.getFaceRenderPriorities();

		int alpha = 0;
		if (faceTransparencies != null && (faceTextures == null || faceTextures[face] == -1)) {
			alpha = (faceTransparencies[face] & 0xFF) << 24;
		}
		int priority = 0;
		if (facePriorities != null) {
			priority = (facePriorities[face] & 0xff) << 16;
		}
		return alpha | priority;
	}
}
