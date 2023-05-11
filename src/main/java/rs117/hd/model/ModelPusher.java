package rs117.hd.model;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.kit.KitType;
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
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.model_overrides.InheritTileColorType;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.PopupUtils;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

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
	private ProceduralGenerator proceduralGenerator;

    public static final int DATUM_PER_FACE = 12;
    public static final int BYTES_PER_DATUM = 4;
	public static final int MAX_MATERIAL_COUNT = (1 << 10) - 1;
	// subtracts the X lowest lightness levels from the formula.
	// helps keep darker colors appropriately dark
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	// multiplier applied to vertex' lightness value.
	// results in greater lightening of lighter colors
	private static final float LIGHTNESS_MULTIPLIER = 3f;
	// the minimum amount by which each color will be lightened
	private static final int BASE_LIGHTEN = 10;

	// temporary arrays for face data & model pusher results
	private static final int[] FOUR_INTS = new int[4];
	private static final int[] TWELVE_INTS = new int[12];
	private static final float[] TWELVE_FLOATS = new float[12];
	private static final int[] MODEL_PUSHER_RESULTS = new int[2];

	// constant array for zeroed out face data
	private static final float[] FLOAT_ZEROS = new float[12];

	private ModelCache modelCache;

//    private int pushes = 0;
//    private int vertexdatahits = 0;
//    private int normaldatahits = 0;
//    private int uvdatahits = 0;

	public void startUp() {
		if (Material.values().length - 1 >= MAX_MATERIAL_COUNT) {
			throw new IllegalStateException(
				"Too many materials (" + Material.values().length + ") to fit into packed material data.");
		}

        if (config.enableModelCaching()) {
            final int size = config.modelCacheSizeMiB();
            try {
                modelCache = new ModelCache(size);
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
                                (size <= 2048 ? "" :
                                    "Your cache size of " + size + " MiB is " + (
                                        size >= 4096 ?
                                            "very large. We would recommend reducing it.<br>" :
                                            "bigger than the default size. Try reducing it.<br>"
                                    )
                                ) +
                                "Normally, a cache size above 2048 MiB is unnecessary, and the game should<br>" +
                                "run acceptably even at 1024 MiB. If you end up having to reduce the size far<br>" +
                                "below 512 MiB, you may be better off disabling the model cache entirely.<br>"
                            )
                        ) +
                        "<br>" +
                        "You can also try closing some other programs on your PC to free up memory.<br>" +
                        "<br>" +
                        "If you need further assistance, please join our " +
                            "<a href=\"" + HdPlugin.DISCORD_URL + "\">Discord</a> server, and<br>" +
                        "drag and drop your client log file into one of our support channels.",
                        new String[] { "Open log folder", "Ok, let me try that..." },
                        i -> { if (i == 0) LinkBrowser.open(RuneLite.LOGS_DIR.toString()); });
                }

                // Allow the model pusher to be used until the plugin has cleanly shut down
                clientThread.invokeLater(plugin::stopPlugin);
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

//    public void printStats() {
//        StringBuilder stats = new StringBuilder();
//        stats.append("\nModel pusher cache stats:\n");
////        stats.append("Vertex cache hit ratio: ").append((float)vertexDataHits/pushes*100).append("%\n");
////        stats.append("Normal cache hit ratio: ").append((float)normalDataHits/pushes*100).append("%\n");
////        stats.append("UV cache hit ratio: ").append((float)uvDataHits/pushes*100).append("%\n");
//        stats.append(vertexDataCache.size()).append(" vertex datas consuming ").append(vertexDataCache.getBytesConsumed()).append(" bytes\n");
//        stats.append(normalDataCache.size()).append(" normal datas consuming ").append(normalDataCache.getBytesConsumed()).append(" bytes\n");
//        stats.append(uvDataCache.size()).append(" uv datas consuming ").append(uvDataCache.getBytesConsumed()).append(" bytes\n");
//        stats.append("totally consuming ").append(this.bytesCached).append(" bytes\n");
//
//        log.debug(stats.toString());
////
//        vertexDataHits = 0;
//        normalDataHits = 0;
//        uvDataHits = 0;
//        pushes = 0;
//    }

    public int[] pushModel(
		long hash, Model model, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer,
		int tileX, int tileY, int tileZ, int preOrientation, @NonNull ModelOverride modelOverride, ObjectType objectType,
		boolean shouldCache
    ) {
        if (modelCache == null) {
            shouldCache = false;
        }

//        pushes++;
        final int faceCount = Math.min(model.getFaceCount(), HdPlugin.MAX_TRIANGLE);
        final int bufferSize = faceCount * DATUM_PER_FACE;
        int vertexLength = 0;
        int uvLength = 0;

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
        vertexBuffer.ensureCapacity(bufferSize);
        normalBuffer.ensureCapacity(bufferSize);
		if (!skipUVs)
        	uvBuffer.ensureCapacity(bufferSize);

        boolean foundCachedVertexData = false;
        boolean foundCachedNormalData = false;
        boolean foundCachedUvData = skipUVs;
        int vertexDataCacheHash = 0;
        int normalDataCacheHash = 0;
        int uvDataCacheHash = 0;

        if (shouldCache) {
            vertexDataCacheHash = modelHasher.calculateVertexCacheHash();
            IntBuffer vertexData = this.modelCache.getVertexData(vertexDataCacheHash);
            foundCachedVertexData = vertexData != null && vertexData.remaining() == bufferSize;
            if (foundCachedVertexData) {
//              vertexDataHits++;
                vertexLength = faceCount * 3;
                vertexBuffer.put(vertexData);
                vertexData.rewind();
            }

            normalDataCacheHash = modelHasher.calculateNormalCacheHash();
            FloatBuffer normalData = this.modelCache.getNormalData(normalDataCacheHash);
            foundCachedNormalData = normalData != null && normalData.remaining() == bufferSize;
            if (foundCachedNormalData) {
//              normalDataHits++;
                normalBuffer.put(normalData);
                normalData.rewind();
            }

            if (!foundCachedUvData) {
                uvDataCacheHash = modelHasher.calculateUvCacheHash(preOrientation, modelOverride);
                FloatBuffer uvData = this.modelCache.getUvData(uvDataCacheHash);
                foundCachedUvData = uvData != null && uvData.remaining() == bufferSize;
                if (foundCachedUvData) {
//                  uvDataHits++;
                    uvLength = faceCount * 3;
                    uvBuffer.put(uvData);
                    uvData.rewind();
                }
            }

            if (foundCachedVertexData && foundCachedNormalData && foundCachedUvData) {
                MODEL_PUSHER_RESULTS[0] = vertexLength;
                MODEL_PUSHER_RESULTS[1] = uvLength;
                return MODEL_PUSHER_RESULTS;
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
				fullVertexData = this.modelCache.takeIntBuffer(bufferSize);
				if (fullVertexData == null) {
					log.error("failed to grab vertex buffer");
					shouldCacheVertexData = false;
				}
			}

			if (shouldCacheNormalData) {
				fullNormalData = this.modelCache.takeFloatBuffer(bufferSize);
				if (fullNormalData == null) {
					log.error("failed to grab normal buffer");
					shouldCacheNormalData = false;
				}
			}

			if (shouldCacheUvData) {
				fullUvData = this.modelCache.takeFloatBuffer(bufferSize);
				if (fullUvData == null) {
					log.error("failed to grab uv buffer");
					shouldCacheUvData = false;
				}
			}
		}

        for (int face = 0; face < faceCount; face++) {
            if (!foundCachedVertexData) {
                int[] tempVertexData = getVertexDataForFace(model, getColorsForFace(hash, model, modelOverride, objectType, tileX, tileY, tileZ, face), face);
                vertexBuffer.put(tempVertexData);
                vertexLength += 3;

                if (shouldCacheVertexData) {
                    fullVertexData.put(tempVertexData);
                }
            }

            if (!foundCachedNormalData) {
                float[] tempNormalData = getNormalDataForFace(model, modelOverride, face);
                normalBuffer.put(tempNormalData);

                if (shouldCacheNormalData) {
                    fullNormalData.put(tempNormalData);
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

				float[] uvData = FLOAT_ZEROS;
				if (materialData != 0) {
					uvData = TWELVE_FLOATS;
					modelOverride.fillUvsForFace(uvData, model, preOrientation, uvType, face);
					uvData[3] = uvData[7] = uvData[11] = materialData;
				}

				uvBuffer.put(uvData);
				if (shouldCacheUvData) {
					fullUvData.put(uvData);
				}
				uvLength += 3;
            }
        }

        if (shouldCacheVertexData) {
            fullVertexData.flip();
            this.modelCache.putVertexData(vertexDataCacheHash, fullVertexData);
        }

        if (shouldCacheNormalData) {
            fullNormalData.flip();
            this.modelCache.putNormalData(normalDataCacheHash, fullNormalData);
        }

        if (shouldCacheUvData) {
            fullUvData.flip();
            this.modelCache.putUvData(uvDataCacheHash, fullUvData);
        }

        MODEL_PUSHER_RESULTS[0] = vertexLength;
        MODEL_PUSHER_RESULTS[1] = uvLength;

        return MODEL_PUSHER_RESULTS;
    }

    private int[] getVertexDataForFace(Model model, int[] faceColors, int face) {
        final int[] xVertices = model.getVerticesX();
        final int[] yVertices = model.getVerticesY();
        final int[] zVertices = model.getVerticesZ();
        final int triA = model.getFaceIndices1()[face];
        final int triB = model.getFaceIndices2()[face];
        final int triC = model.getFaceIndices3()[face];

        TWELVE_INTS[0] = xVertices[triA];
        TWELVE_INTS[1] = yVertices[triA];
        TWELVE_INTS[2] = zVertices[triA];
        TWELVE_INTS[3] = faceColors[3] | faceColors[0];
        TWELVE_INTS[4] = xVertices[triB];
        TWELVE_INTS[5] = yVertices[triB];
        TWELVE_INTS[6] = zVertices[triB];
        TWELVE_INTS[7] = faceColors[3] | faceColors[1];
        TWELVE_INTS[8] = xVertices[triC];
        TWELVE_INTS[9] = yVertices[triC];
        TWELVE_INTS[10] = zVertices[triC];
        TWELVE_INTS[11] = faceColors[3] | faceColors[2];

        return TWELVE_INTS;
    }

    private float[] getNormalDataForFace(Model model, @NonNull ModelOverride modelOverride, int face) {
		int terrainData = SceneUploader.packTerrainData(false, 0, WaterType.NONE, 0);
		if (terrainData == 0 && (modelOverride.flatNormals || model.getFaceColors3()[face] == -1)) {
			return FLOAT_ZEROS;
		}

        final int triA = model.getFaceIndices1()[face];
        final int triB = model.getFaceIndices2()[face];
        final int triC = model.getFaceIndices3()[face];
        final int[] xVertexNormals = model.getVertexNormalsX();
        final int[] yVertexNormals = model.getVertexNormalsY();
        final int[] zVertexNormals = model.getVertexNormalsZ();

        TWELVE_FLOATS[0] = xVertexNormals[triA];
        TWELVE_FLOATS[1] = yVertexNormals[triA];
        TWELVE_FLOATS[2] = zVertexNormals[triA];
        TWELVE_FLOATS[3] = terrainData;
        TWELVE_FLOATS[4] = xVertexNormals[triB];
        TWELVE_FLOATS[5] = yVertexNormals[triB];
        TWELVE_FLOATS[6] = zVertexNormals[triB];
        TWELVE_FLOATS[7] = terrainData;
        TWELVE_FLOATS[8] = xVertexNormals[triC];
        TWELVE_FLOATS[9] = yVertexNormals[triC];
        TWELVE_FLOATS[10] = zVertexNormals[triC];
        TWELVE_FLOATS[11] = terrainData;

        return TWELVE_FLOATS;
    }

    public int packMaterialData(Material material, @NonNull ModelOverride modelOverride, UvType uvType, boolean isOverlay) {
		// TODO: only the lower 24 bits can be safely used due to imprecise casting to float in shaders
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

    private int[] getColorsForFace(long hash, Model model, @NonNull ModelOverride modelOverride, ObjectType objectType, int tileX, int tileY, int tileZ, int face) {
        final int triA = model.getFaceIndices1()[face];
        final int triB = model.getFaceIndices2()[face];
        final int triC = model.getFaceIndices3()[face];
        final byte[] faceTransparencies = model.getFaceTransparencies();
        final short[] faceTextures = model.getFaceTextures();
        final int[] xVertices = model.getVerticesX();
        final int[] yVertices = model.getVerticesY();
        final int[] zVertices = model.getVerticesZ();

        int heightA = yVertices[triA];
        int heightB = yVertices[triB];
        int heightC = yVertices[triC];

        // Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
        if (plugin.configHideBakedEffects && isBakedGroundShading(face, heightA, heightB, heightC, faceTransparencies, faceTextures)) {
            boolean removeBakedLighting = modelOverride.removeBakedLighting;

            if (ModelHash.getType(hash) == ModelHash.TYPE_PLAYER) {
                int index = ModelHash.getIdOrIndex(hash);
                Player[] players = client.getCachedPlayers();
                Player player = index >= 0 && index < players.length ? players[index] : null;
                if (player != null && player.getPlayerComposition().getEquipmentId(KitType.WEAPON) == ItemID.MAGIC_CARPET) {
                    removeBakedLighting = true;
                }
            }

            if (removeBakedLighting) {
                FOUR_INTS[0] = 0;
                FOUR_INTS[1] = 0;
                FOUR_INTS[2] = 0;
                FOUR_INTS[3] = 0xFF << 24;
                return FOUR_INTS;
            }
        }

        int color1 = model.getFaceColors1()[face];
        int color2 = model.getFaceColors2()[face];
        int color3 = model.getFaceColors3()[face];
        final byte overrideAmount = model.getOverrideAmount();
        final byte overrideHue = model.getOverrideHue();
        final byte overrideSat = model.getOverrideSaturation();
        final byte overrideLum = model.getOverrideLuminance();
        final int[] xVertexNormals = model.getVertexNormalsX();
        final int[] yVertexNormals = model.getVertexNormalsY();
        final int[] zVertexNormals = model.getVertexNormalsZ();
        final Tile tile = client.getScene().getTiles()[tileZ][tileX][tileY];

        if (color3 == -2) {
            FOUR_INTS[0] = 0;
            FOUR_INTS[1] = 0;
            FOUR_INTS[2] = 0;
            FOUR_INTS[3] = 0xFF << 24;
            return FOUR_INTS;
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

            float[] L = HDUtils.lightDirModel;
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
        if (!plugin.configReduceOverExposure) {
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

                    Overlay overlay = Overlay.getOverlay(client.getScene().getOverlayIds()[tileZ][tileX][tileY], tile, client, plugin);
                    if (overlay != Overlay.NONE) {
                        tileColorHSL = proceduralGenerator.recolorOverlay(overlay, tileColorHSL);
                    } else {
                        Underlay underlay = Underlay.getUnderlay(client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, plugin);
                        tileColorHSL = proceduralGenerator.recolorUnderlay(underlay, tileColorHSL);
                    }

                    color1H = color2H = color3H = tileColorHSL[0];
                    color1S = color2S = color3S = tileColorHSL[1];
                    color1L = color2L = color3L = tileColorHSL[2];

                } else if (tileModel != null && tileModel.getTriangleTextureId() == null) {
                    int faceColorIndex = -1;
                    for (int i = 0; i < tileModel.getTriangleColorA().length; i++) {
                        boolean isOverlayFace = proceduralGenerator.isOverlayFace(tile, i);
                        // Use underlay if the tile does not have an overlay, useful for rocks in cave corners.
                        if(modelOverride.inheritTileColorType == InheritTileColorType.UNDERLAY || tileModel.getModelOverlay() == 0) {
                            // pulling the color from UNDERLAY is more desirable for green grass tiles
                            // OVERLAY pulls in path color which is not desirable for grass next to paths
                            if (!isOverlayFace) {
                                faceColorIndex = i;
                                break;
                            }
                        }
                        else if(modelOverride.inheritTileColorType == InheritTileColorType.OVERLAY) {
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

                            Underlay underlay = Underlay.getUnderlay(client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, plugin);
                            tileColorHSL = proceduralGenerator.recolorUnderlay(underlay, tileColorHSL);

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
            int[][] tzHaarRecolored = proceduralGenerator.recolorTzHaar(modelOverride, heightA, heightB, heightC, packedAlphaPriority, objectType, color1S, color1L, color2S, color2L, color3S, color3L);
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

        FOUR_INTS[0] = color1;
        FOUR_INTS[1] = color2;
        FOUR_INTS[2] = color3;
        FOUR_INTS[3] = packedAlphaPriority;

        return FOUR_INTS;
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
