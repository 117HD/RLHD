package rs117.hd.model;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.kit.KitType;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.data.materials.Material;
import rs117.hd.data.materials.Overlay;
import rs117.hd.data.materials.Underlay;
import rs117.hd.scene.model_overrides.InheritTileColorType;
import rs117.hd.scene.model_overrides.ModelOverride;
import rs117.hd.scene.model_overrides.ObjectType;
import rs117.hd.scene.model_overrides.TzHaarRecolorType;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.buffer.GpuFloatBuffer;
import rs117.hd.utils.buffer.GpuIntBuffer;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static rs117.hd.utils.HDUtils.dotNormal3Lights;

/**
 * Pushes models
 */
@Singleton
@Slf4j
public class ModelPusher {
    @Inject
    private HdPlugin hdPlugin;

    @Inject
    private HdPluginConfig config;

    @Inject
    private Client client;

    @Inject
    private ProceduralGenerator proceduralGenerator;

    @Inject
    private ModelHasher modelHasher;

    private final ModelCache modelCache = new ModelCache();
    public static final int DATUM_PER_FACE = 12;
    public static final int BYTES_PER_DATUM = 4;

    private boolean started = false;

//    private int pushes = 0;
//    private int vertexdatahits = 0;
//    private int normaldatahits = 0;
//    private int uvdatahits = 0;

    public void startUp() {
        if (!started) {
            this.modelCache.init(hdPlugin, config);
            this.started = true;
        }
    }

    public void shutDown() {
        if (started) {
            this.modelCache.shutDown();
            this.started = false;
        }
    }

    // subtracts the X lowest lightness levels from the formula.
    // helps keep darker colors appropriately dark
    private static final int ignoreLowLightness = 3;
    // multiplier applied to vertex' lightness value.
    // results in greater lightening of lighter colors
    private static final float lightnessMultiplier = 3f;
    // the minimum amount by which each color will be lightened
    private static final int baseLighten = 10;
    // same thing but for the normalBuffer and uvBuffer
    private final static float[] zeroFloats = new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private final static int[] twoInts = new int[2];
    private final static int[] fourInts = new int[4];
    private final static int[] twelveInts = new int[12];
    private final static float[] twelveFloats = new float[12];

    public void clearModelCache() {
        this.modelCache.clear();
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

    public int[] pushModel(long hash, Model model, GpuIntBuffer vertexBuffer, GpuFloatBuffer uvBuffer, GpuFloatBuffer normalBuffer, int tileX, int tileY, int tileZ, @NonNull ModelOverride modelOverride, ObjectType objectType, boolean noCache) {
//        pushes++;
        final int faceCount = Math.min(model.getFaceCount(), HdPlugin.MAX_TRIANGLE);
        final int bufferSize = faceCount * DATUM_PER_FACE;
        int vertexLength = 0;
        int uvLength = 0;

        // ensure capacity upfront
        vertexBuffer.ensureCapacity(bufferSize);
        normalBuffer.ensureCapacity(bufferSize);
        uvBuffer.ensureCapacity(bufferSize);

        boolean cachedVertexData = false;
        boolean cachedNormalData = false;
        boolean cachedUvData = false;
        int vertexDataCacheHash = 0;
        int normalDataCacheHash = 0;
        int uvDataCacheHash = 0;

        if (!noCache) {
            vertexDataCacheHash = modelHasher.calculateVertexCacheHash();
            normalDataCacheHash = modelHasher.calculateNormalCacheHash();
            uvDataCacheHash = modelHasher.calculateUvCacheHash(modelOverride);

            IntBuffer vertexData = this.modelCache.getVertexData(vertexDataCacheHash);
            cachedVertexData = vertexData != null && vertexData.remaining() == bufferSize;
            if (cachedVertexData) {
//                vertexDataHits++;
                vertexLength = faceCount * 3;
                vertexBuffer.put(vertexData);
                vertexData.rewind();
            }

            FloatBuffer normalData = this.modelCache.getNormalData(normalDataCacheHash);
            cachedNormalData = normalData != null && normalData.remaining() == bufferSize;
            if (cachedNormalData) {
//                normalDataHits++;
                normalBuffer.put(normalData);
                normalData.rewind();
            }

            FloatBuffer uvData = this.modelCache.getUvData(uvDataCacheHash);
            cachedUvData = uvData != null;
            if (cachedUvData) {
//                uvDataHits++;
                uvLength = 3 * (uvData.remaining() / DATUM_PER_FACE);
                uvBuffer.put(uvData);
                uvData.rewind();
            }

            if (cachedVertexData && cachedUvData && cachedNormalData) {
                twoInts[0] = vertexLength;
                twoInts[1] = uvLength;
                return twoInts;
            }
        }

        IntBuffer fullVertexData = null;
        FloatBuffer fullNormalData = null;
        FloatBuffer fullUvData = null;

        boolean cachingVertexData = !cachedVertexData && !noCache;
        if (cachingVertexData) {
            fullVertexData = this.modelCache.takeIntBuffer(bufferSize);
            if (fullVertexData == null) {
                log.error("failed to grab vertex buffer");
                cachingVertexData = false;
            }
        }

        boolean cachingNormalData = !cachedNormalData && !noCache;
        if (cachingNormalData) {
            fullNormalData = this.modelCache.takeFloatBuffer(bufferSize);
            if (fullNormalData == null) {
                log.error("failed to grab normal buffer");
                cachingNormalData = false;
            }
        }

        boolean cachingUvData = !cachedUvData && !noCache;
        if (cachingUvData) {
            fullUvData = this.modelCache.takeFloatBuffer(bufferSize);
            if (fullUvData == null) {
                log.error("failed to grab uv buffer");
                cachingUvData = false;
            }
        }

        boolean hideBakedEffects = config.hideBakedEffects();
        for (int face = 0; face < faceCount; face++) {
            if (!cachedVertexData) {
                int[] tempVertexData = getVertexDataForFace(model, getColorsForFace(hash, model, modelOverride, objectType, tileX, tileY, tileZ, face, hideBakedEffects), face);
                vertexBuffer.put(tempVertexData);
                vertexLength += 3;

                if (cachingVertexData) {
                    fullVertexData.put(tempVertexData);
                }
            }

            if (!cachedNormalData) {
                float[] tempNormalData = getNormalDataForFace(model, modelOverride, face);
                normalBuffer.put(tempNormalData);

                if (cachingNormalData) {
                    fullNormalData.put(tempNormalData);
                }
            }

            if (!cachedUvData) {
                float[] tempUvData = getUvDataForFace(model, modelOverride, face);
                if (tempUvData != null) {
                    uvBuffer.put(tempUvData);
                    uvLength += 3;

                    if (cachingUvData) {
                        fullUvData.put(tempUvData);
                    }
                }
            }
        }

        if (cachingVertexData) {
            fullVertexData.flip();
            this.modelCache.putVertexData(vertexDataCacheHash, fullVertexData);
        }

        if (cachingNormalData) {
            fullNormalData.flip();
            this.modelCache.putNormalData(normalDataCacheHash, fullNormalData);
        }

        if (cachingUvData) {
            fullUvData.flip();
            this.modelCache.putUvData(uvDataCacheHash, fullUvData);
        }

        twoInts[0] = vertexLength;
        twoInts[1] = uvLength;

        return twoInts;
    }

    private int[] getVertexDataForFace(Model model, int[] faceColors, int face) {
        final int[] xVertices = model.getVerticesX();
        final int[] yVertices = model.getVerticesY();
        final int[] zVertices = model.getVerticesZ();
        final int triA = model.getFaceIndices1()[face];
        final int triB = model.getFaceIndices2()[face];
        final int triC = model.getFaceIndices3()[face];

        twelveInts[0] = xVertices[triA];
        twelveInts[1] = yVertices[triA];
        twelveInts[2] = zVertices[triA];
        twelveInts[3] = faceColors[3] | faceColors[0];
        twelveInts[4] = xVertices[triB];
        twelveInts[5] = yVertices[triB];
        twelveInts[6] = zVertices[triB];
        twelveInts[7] = faceColors[3] | faceColors[1];
        twelveInts[8] = xVertices[triC];
        twelveInts[9] = yVertices[triC];
        twelveInts[10] = zVertices[triC];
        twelveInts[11] = faceColors[3] | faceColors[2];

        return twelveInts;
    }

    private float[] getNormalDataForFace(Model model, @NonNull ModelOverride modelOverride, int face) {
        if (modelOverride.flatNormals || model.getFaceColors3()[face] == -1) {
            return zeroFloats;
        }

        final int triA = model.getFaceIndices1()[face];
        final int triB = model.getFaceIndices2()[face];
        final int triC = model.getFaceIndices3()[face];
        final int[] xVertexNormals = model.getVertexNormalsX();
        final int[] yVertexNormals = model.getVertexNormalsY();
        final int[] zVertexNormals = model.getVertexNormalsZ();

        twelveFloats[0] = xVertexNormals[triA];
        twelveFloats[1] = yVertexNormals[triA];
        twelveFloats[2] = zVertexNormals[triA];
        twelveFloats[3] = 0;
        twelveFloats[4] = xVertexNormals[triB];
        twelveFloats[5] = yVertexNormals[triB];
        twelveFloats[6] = zVertexNormals[triB];
        twelveFloats[7] = 0;
        twelveFloats[8] = xVertexNormals[triC];
        twelveFloats[9] = yVertexNormals[triC];
        twelveFloats[10] = zVertexNormals[triC];
        twelveFloats[11] = 0;

        return twelveFloats;
    }

    private float[] getUvDataForFace(Model model, @NonNull ModelOverride modelOverride, int face) {
        final short[] faceTextures = model.getFaceTextures();
        final float[] uv = model.getFaceTextureUVCoordinates();

        Material material = Material.NONE;

        boolean isVanillaTextured = faceTextures != null && uv != null && faceTextures[face] != -1;
        if (isVanillaTextured) {
            if (hdPlugin.configModelTextures) {
                material = modelOverride.textureMaterial;
            }

            if (material == Material.NONE) {
                material = Material.getTexture(faceTextures[face]);
            }
        } else if (hdPlugin.configModelTextures) {
            material = modelOverride.baseMaterial;
        }

        if (material == Material.NONE) {
            return faceTextures == null ? null : zeroFloats;
        }

        int packedMaterialData = packMaterialData(material, false);

        switch (modelOverride.uvType) {
            case GROUND_PLANE:
                final int triA = model.getFaceIndices1()[face];
                final int triB = model.getFaceIndices2()[face];
                final int triC = model.getFaceIndices3()[face];

                final int[] xVertices = model.getVerticesX();
                final int[] zVertices = model.getVerticesZ();

                twelveFloats[0] = packedMaterialData;
                twelveFloats[1] = (xVertices[triA] % Perspective.LOCAL_TILE_SIZE) / (float) Perspective.LOCAL_TILE_SIZE;
                twelveFloats[2] = (zVertices[triA] % Perspective.LOCAL_TILE_SIZE) / (float) Perspective.LOCAL_TILE_SIZE;
                twelveFloats[3] = 0;
                twelveFloats[4] = packedMaterialData;
                twelveFloats[5] = (xVertices[triB] % Perspective.LOCAL_TILE_SIZE) / (float) Perspective.LOCAL_TILE_SIZE;
                twelveFloats[6] = (zVertices[triB] % Perspective.LOCAL_TILE_SIZE) / (float) Perspective.LOCAL_TILE_SIZE;
                twelveFloats[7] = 0;
                twelveFloats[8] = packedMaterialData;
                twelveFloats[9] = (xVertices[triC] % Perspective.LOCAL_TILE_SIZE) / (float) Perspective.LOCAL_TILE_SIZE;
                twelveFloats[10] = (zVertices[triC] % Perspective.LOCAL_TILE_SIZE) / (float) Perspective.LOCAL_TILE_SIZE;
                twelveFloats[11] = 0;
                break;
            case VANILLA:
                if (isVanillaTextured) {
                    int idx = face * 6;
                    twelveFloats[0] = packedMaterialData;
                    twelveFloats[1] = uv[idx];
                    twelveFloats[2] = uv[idx + 1];
                    twelveFloats[3] = 0;
                    twelveFloats[4] = packedMaterialData;
                    twelveFloats[5] = uv[idx + 2];
                    twelveFloats[6] = uv[idx + 3];
                    twelveFloats[7] = 0;
                    twelveFloats[8] = packedMaterialData;
                    twelveFloats[9] = uv[idx + 4];
                    twelveFloats[10] = uv[idx + 5];
                    twelveFloats[11] = 0;
                    break;
                }
                // fall through
            case GEOMETRY:
            default:
                twelveFloats[0] = packedMaterialData;
                twelveFloats[1] = 0;
                twelveFloats[2] = 0;
                twelveFloats[3] = 0;
                twelveFloats[4] = packedMaterialData;
                twelveFloats[5] = 1;
                twelveFloats[6] = 0;
                twelveFloats[7] = 0;
                twelveFloats[8] = packedMaterialData;
                twelveFloats[9] = 0;
                twelveFloats[10] = 1;
                twelveFloats[11] = 0;
                break;
        }

        return twelveFloats;
    }

    public int packMaterialData(Material material, boolean isOverlay) {
        return material.ordinal() << 1 | (isOverlay ? 1 : 0);
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

    private int[] getColorsForFace(long hash, Model model, @NonNull ModelOverride modelOverride, ObjectType objectType, int tileX, int tileY, int tileZ, int face, boolean hideBakedEffects) {
        final int triA = model.getFaceIndices1()[face];
        final int triB = model.getFaceIndices2()[face];
        final int triC = model.getFaceIndices3()[face];
        final byte[] faceTransparencies = model.getFaceTransparencies();
        final short[] faceTextures = model.getFaceTextures();
        final int[] yVertices = model.getVerticesY();

        int heightA = yVertices[triA];
        int heightB = yVertices[triB];
        int heightC = yVertices[triC];

        // Hide fake shadows or lighting that is often baked into models by making the fake shadow transparent
        if (hideBakedEffects && isBakedGroundShading(face, heightA, heightB, heightC, faceTransparencies, faceTextures)) {
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
                fourInts[0] = 0;
                fourInts[1] = 0;
                fourInts[2] = 0;
                fourInts[3] = 0xFF << 24;
                return fourInts;
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
            fourInts[0] = 0;
            fourInts[1] = 0;
            fourInts[2] = 0;
            fourInts[3] = 0xFF << 24;
            return fourInts;
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
        int lightenA = (int) (Math.max((color1L - ignoreLowLightness), 0) * lightnessMultiplier) + baseLighten;
        float dotA = Math.max(dotNormal3Lights(new float[]{
            xVertexNormals[triA],
            yVertexNormals[triA],
            zVertexNormals[triA],
        }), 0);
        color1L = (int) HDUtils.lerp(color1L, lightenA, dotA);

        int lightenB = (int) (Math.max((color2L - ignoreLowLightness), 0) * lightnessMultiplier) + baseLighten;
        float dotB = Math.max(dotNormal3Lights(new float[]{
            xVertexNormals[triB],
            yVertexNormals[triB],
            zVertexNormals[triB],
        }), 0);
        color2L = (int) HDUtils.lerp(color2L, lightenB, dotB);

        int lightenC = (int) (Math.max((color3L - ignoreLowLightness), 0) * lightnessMultiplier) + baseLighten;
        float dotC = Math.max(dotNormal3Lights(new float[]{
            xVertexNormals[triC],
            yVertexNormals[triC],
            zVertexNormals[triC],
        }), 0);
        color3L = (int) HDUtils.lerp(color3L, lightenC, dotC);

        int maxBrightness1 = (int) HDUtils.lerp(127, 55, (float) Math.pow((float) color1S / 0x7, .05));
        int maxBrightness2 = (int) HDUtils.lerp(127, 55, (float) Math.pow((float) color2S / 0x7, .05));
        int maxBrightness3 = (int) HDUtils.lerp(127, 55, (float) Math.pow((float) color3S / 0x7, .05));
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
                if (tilePaint != null && tilePaint.getTexture() == -1 && tilePaint.getRBG() != 0) {
                    // pull any corner color as either one should be OK
                    tileColorHSL = HDUtils.colorIntToHSL(tilePaint.getSwColor());

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

                    Overlay overlay = Overlay.getOverlay((int) client.getScene().getOverlayIds()[tileZ][tileX][tileY], tile, client, config);
                    Underlay underlay = Underlay.getUnderlay((int) client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, config);
                    if (overlay != Overlay.NONE) {
                        tileColorHSL = proceduralGenerator.recolorOverlay(overlay, tileColorHSL);
                    } else {
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
                        tileColorHSL = HDUtils.colorIntToHSL(tileModel.getTriangleColorA()[faceColorIndex]);

                        Underlay underlay = Underlay.getUnderlay((int) client.getScene().getUnderlayIds()[tileZ][tileX][tileY], tile, client, config);
                        tileColorHSL = proceduralGenerator.recolorUnderlay(underlay, tileColorHSL);

                        color1H = color2H = color3H = tileColorHSL[0];
                        color1S = color2S = color3S = tileColorHSL[1];
                        color1L = color2L = color3L = tileColorHSL[2];
                    }
                }
            }
        }

        int packedAlphaPriority = getPackedAlphaPriority(model, face);

        if (hdPlugin.configTzhaarHD && modelOverride.tzHaarRecolorType != TzHaarRecolorType.NONE) {
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

        fourInts[0] = color1;
        fourInts[1] = color2;
        fourInts[2] = color3;
        fourInts[3] = packedAlphaPriority;

        return fourInts;
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
