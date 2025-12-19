package rs117.hd.scene;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.api.RLHDAPI;
import rs117.hd.api.RLHDEvent;
import rs117.hd.api.RLHDSubscribe;
import rs117.hd.api.RLHDUnsubscribe;
import rs117.hd.scene.ground_materials.GroundMaterial;
import rs117.hd.scene.materials.Material;
import rs117.hd.scene.water_types.WaterType;
import rs117.hd.utils.ColorUtils;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.tile_overrides.TileOverride.NONE;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.MathUtils.*;

@Singleton
public class MinimapRenderer {
	@Inject
	ProceduralGenerator proceduralGenerator;

	@Inject
	private Client client;

	@Inject
	private HdPlugin plugin;

	@Inject
	private ClientThread clientThread;

	@Inject
	private HdPluginConfig config;

	@Inject
	private EnvironmentManager environmentManager;

	@Inject
	private TextureManager textureManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private RLHDAPI rlhdAPI;

	public boolean updateMinimapLighting;

	public int[][][][] minimapTilePaintColorsLighting;
	public int[][][][][] minimapTileModelColorsLighting;

	public void startUp() {
		minimapTilePaintColorsLighting = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][8];
		minimapTileModelColorsLighting = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][6][6];
	}

	public void clear(SceneContext sceneContext) {
		minimapTilePaintColorsLighting = null;
		minimapTileModelColorsLighting = null;
		sceneContext.minimapTileModelColors = null;
		sceneContext.minimapTilePaintColors = null;
	}

	public boolean HD117MapEnabled()
	{
		return "true".equals(configManager.getConfiguration("runelite", "hdminimapplugin"))
			   && "HD117".equals(configManager.getConfiguration("hdminimap", "minimapStyle"));
	}

	public void prepareScene(SceneContext sceneContext) {
		if (!HD117MapEnabled()) return;

		if (sceneContext.minimapTilePaintColors == null) {
			sceneContext.minimapTilePaintColors = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][8];
			sceneContext.minimapTileModelColors = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][6][6];
		}

		final Scene scene = sceneContext.scene;

		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
					Tile tile = scene.getExtendedTiles()[z][x][y];
					if (tile == null) continue;
					processTilePaint(scene, sceneContext, tile, z, x, y);
					processTileModel(scene, sceneContext, tile, z, x, y);
				}
			}
		}
		updateMinimapLighting = true;
	}

	private Material getWater(WaterType type) {
		if (type == WaterType.ICE || type == WaterType.ICE_FLAT) {
			return materialManager.getMaterial("WATER_ICE");
		} else if (type == WaterType.SWAMP_WATER || type == WaterType.SWAMP_WATER_FLAT) {
			return materialManager.getMaterial("SWAMP_WATER_FLAT");
		}

		return materialManager.getMaterial("WATER_FLAT");
	}

	private final boolean minimapGroundBlending = false;
	private final boolean minimapGroundTextures = false;

	private void processTilePaint(Scene scene, SceneContext sceneContext, Tile tile, int z, int x, int y) {
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint == null) {
			return;
		}

		int[] colors = { paint.getSwColor(), paint.getSeColor(), paint.getNwColor(), paint.getNeColor() };
		Material[] materials = new Material[4];

		Arrays.fill(materials, Material.NONE);

		final Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileZ = tile.getRenderLevel();
		boolean hiddenTile = paint.getNwColor() == 12345678;
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		int[] vertexKeys = ProceduralGenerator.tileVertexKeys(sceneContext, tile);
		int swVertexKey = vertexKeys[0];
		int seVertexKey = vertexKeys[1];
		int nwVertexKey = vertexKeys[2];
		int neVertexKey = vertexKeys[3];
		int hiddenRBG = ColorUtils.srgbToPackedHsl(ColorUtils.srgba(paint.getRBG()));

		// Ignore certain tiles that aren't supposed to be visible,
		// but which we can still make a height-adjusted version of for underwater


		int textureId = paint.getTexture();

		var override = tileOverrideManager.getOverride(sceneContext, tile);
		WaterType waterType = proceduralGenerator.seasonalWaterType(override, paint.getTexture());

		if (waterType == WaterType.NONE) {
			if (textureId != -1) {
				var material = materialManager.fromVanillaTexture(textureId);
				// Disable tile overrides for newly introduced vanilla textures
				if (material.isFallbackVanillaMaterial)
					override = NONE;
				materials[0] = materials[1] = materials[2] = materials[3] = material;
			}


			boolean useBlendedMaterialAndColor =
				minimapGroundBlending &&
				textureId == -1 &&
				!proceduralGenerator.useDefaultColor(tile, override);
			GroundMaterial groundMaterial = null;
			if (override != NONE) {
				groundMaterial = override.groundMaterial;
				if (!useBlendedMaterialAndColor) {
					colors[0] = override.modifyColor(colors[0]);
					colors[1] = override.modifyColor(colors[1]);
					colors[2] = override.modifyColor(colors[2]);
					colors[3] = override.modifyColor(colors[3]);
				}
			} else if (textureId == -1) {
				// Fall back to the default ground material if the tile is untextured
				groundMaterial = override.groundMaterial;
			}

			if (useBlendedMaterialAndColor) {
				// get the vertices' colors and textures from hashmaps
				colors[0] = sceneContext.vertexTerrainColor.getOrDefault(swVertexKey, colors[0]);
				colors[1] = sceneContext.vertexTerrainColor.getOrDefault(seVertexKey, colors[1]);
				colors[2] = sceneContext.vertexTerrainColor.getOrDefault(neVertexKey, colors[2]);
				colors[3] = sceneContext.vertexTerrainColor.getOrDefault(nwVertexKey, colors[3]);

				if (minimapGroundTextures) {
					materials[0] = sceneContext.vertexTerrainTexture.getOrDefault(swVertexKey, materials[0]);
					materials[1] = sceneContext.vertexTerrainTexture.getOrDefault(seVertexKey, materials[1]);
					materials[2] = sceneContext.vertexTerrainTexture.getOrDefault(neVertexKey, materials[2]);
					materials[3] = sceneContext.vertexTerrainTexture.getOrDefault(nwVertexKey, materials[3]);
				}
			} else if (minimapGroundTextures && groundMaterial != null) {
				materials[0] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY);
				materials[1] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY);
				materials[2] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX, baseY + tileY + 1);
				materials[3] = groundMaterial.getRandomMaterial(tileZ, baseX + tileX + 1, baseY + tileY + 1);
			}
		}
		else
		{
			Arrays.fill(colors, ColorUtils.srgbToPackedHsl(waterType.getSurfaceColor()));
			Arrays.fill(materials, getWater(waterType));
		}

		int[] texturePackedColors = new int[4];
		for (int i = 0; i < 4; i++) {
			if (hiddenTile) {
				colors[i] = hiddenRBG;
			}


			float[] colorRGB = ColorUtils.srgbToLinear(ColorUtils.packedHslToSrgb(colors[i]));
			texturePackedColors[i] = multiplyAndPackColors(materials[i].averageColor, colorRGB);

			if (hiddenTile) {
				texturePackedColors[i] = hiddenRBG;
			} else {
				texturePackedColors[i] =
					texturePackedColors[0] & 0xFF80 | texturePackedColors[i] & 0x7F; // Ensure hue and saturation consistency
			}
		}

		// Update sceneContext with color information
		System.arraycopy(colors, 0, sceneContext.minimapTilePaintColors[z][x][y], 0, colors.length);
		System.arraycopy(texturePackedColors, 0, sceneContext.minimapTilePaintColors[z][x][y], colors.length, texturePackedColors.length);
	}

	private void processTileModel(Scene scene, SceneContext sceneContext, Tile tile, int z, int x, int y) {
		SceneTileModel model = tile.getSceneTileModel();
		if (model == null) {
			return;
		}
		final int[] faceColorA = model.getTriangleColorA();
		final int[] faceColorB = model.getTriangleColorB();
		final int[] faceColorC = model.getTriangleColorC();
		final int[] faceTextures = model.getTriangleTextureId();
		final int faceCount = model.getFaceX().length;

		Point tilePoint = tile.getSceneLocation();
		final int tileX = tilePoint.getX();
		final int tileY = tilePoint.getY();
		final int tileZ = tile.getRenderLevel();
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();

		final int tileExX = tileX + sceneContext.sceneOffset;
		final int tileExY = tileY + sceneContext.sceneOffset;

		int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);
		int overlayId = OVERLAY_FLAG | scene.getOverlayIds()[tileZ][tileExX][tileExY];
		int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];

		for (int face = 0; face < faceCount; ++face) {

			int[] colors = { faceColorA[face], faceColorB[face], faceColorC[face] };
			Material[] materials = new Material[3];
			Arrays.fill(materials, Material.NONE);

			int[][] localVertices = ProceduralGenerator.faceLocalVertices(tile, face);

			int[] vertexKeys = ProceduralGenerator.faceVertexKeys(tile, face);
			int vertexKeyA = vertexKeys[0];
			int vertexKeyB = vertexKeys[1];
			int vertexKeyC = vertexKeys[2];

			int textureId;

			WaterType waterType;



			boolean isOverlay = ProceduralGenerator.isOverlayFace(tile, face);
			var override = tileOverrideManager.getOverride(sceneContext, tile, worldPos, isOverlay ? overlayId : underlayId);
			textureId = faceTextures == null ? -1 : faceTextures[face];
			waterType = proceduralGenerator.seasonalWaterType(override, textureId);
			if (waterType == WaterType.NONE) {
				if (textureId != -1) {
					var material = materialManager.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material.isFallbackVanillaMaterial)
						override = NONE;
					materials[0] = materials[1] = materials[2] = material;
				}

				GroundMaterial groundMaterial = null;

				boolean useBlendedMaterialAndColor =
					minimapGroundBlending &&
					textureId == -1 &&
					!(isOverlay && proceduralGenerator.useDefaultColor(tile, override));
				if (override != NONE) {
					groundMaterial = override.groundMaterial;
					if (!useBlendedMaterialAndColor) {
						colors[0] = override.modifyColor(colors[0]);
						colors[1] = override.modifyColor(colors[1]);
						colors[2] = override.modifyColor(colors[2]);
					}
				} else if (textureId == -1) {
					// Fall back to the default ground material if the tile is untextured
					groundMaterial = override.groundMaterial;
				}

				if (useBlendedMaterialAndColor) {
					// get the vertices' colors and textures from hashmaps
					colors[0] = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyA, colors[0]);
					colors[1] = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyB, colors[1]);
					colors[2] = sceneContext.vertexTerrainColor.getOrDefault(vertexKeyC, colors[2]);

					if (minimapGroundTextures) {
						materials[0] = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyA, materials[0]);
						materials[1] = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyB, materials[1]);
						materials[2] = sceneContext.vertexTerrainTexture.getOrDefault(vertexKeyC, materials[2]);
					}
				} else if (minimapGroundTextures && groundMaterial != null) {
					materials[0] = groundMaterial.getRandomMaterial(
						tileZ,
						baseX + tileX + (int) Math.floor((float) localVertices[0][0] / LOCAL_TILE_SIZE),
						baseY + tileY + (int) Math.floor((float) localVertices[0][1] / LOCAL_TILE_SIZE)
					);
					materials[1] = groundMaterial.getRandomMaterial(
						tileZ,
						baseX + tileX + (int) Math.floor((float) localVertices[1][0] / LOCAL_TILE_SIZE),
						baseY + tileY + (int) Math.floor((float) localVertices[1][1] / LOCAL_TILE_SIZE)
					);
					materials[2] = groundMaterial.getRandomMaterial(
						tileZ,
						baseX + tileX + (int) Math.floor((float) localVertices[2][0] / LOCAL_TILE_SIZE),
						baseY + tileY + (int) Math.floor((float) localVertices[2][1] / LOCAL_TILE_SIZE)
					);
				}
			} else {
				Arrays.fill(colors, ColorUtils.srgbToPackedHsl(waterType.getSurfaceColor()));
				Arrays.fill(materials, getWater(waterType));
			}

			float[][] linearColors = new float[colors.length][];
			for (int i = 0; i < colors.length; i++) {
				linearColors[i] = ColorUtils.srgbToLinear(ColorUtils.packedHslToSrgb(colors[i]));
			}


			// Store the processed colors directly
			System.arraycopy(colors, 0, sceneContext.minimapTileModelColors[z][x][y][face], 0, colors.length);

			// Process materials and store them
			int firstMaterialColor = 0;
			for (int i = 0; i < materials.length; i++) {
				int packedColor = multiplyAndPackColors(materials[i].averageColor, linearColors[i]);

				if (i == 0) {
					firstMaterialColor = packedColor;
					sceneContext.minimapTileModelColors[z][x][y][face][3 + i] = packedColor;
				} else {
					// Applying the blending operation separately
					int blendedColor = firstMaterialColor & 0xFF80 | packedColor & 0x7F;
					sceneContext.minimapTileModelColors[z][x][y][face][3 + i] = blendedColor;
				}
			}

		}
	}

	public int multiplyAndPackColors(float[] color1, float[] color2) {
		// Ensure each color array has exactly 3 elements (R, G, B)
		if (color1.length != 3 || color2.length != 3) {
			throw new IllegalArgumentException("Each color array must have exactly 3 elements.");
		}

		float red = color1[0] * color2[0];
		float green = color1[1] * color2[1];
		float blue = color1[2] * color2[2];

		return ColorUtils.srgbToPackedHsl(ColorUtils.linearToSrgb(new float[] { red, green, blue }));
	}



	public void applyLighting(SceneContext sceneContext) {
		if (!HD117MapEnabled()) return;

		if (sceneContext.minimapTilePaintColors == null) {
			sceneContext.minimapTilePaintColors = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][8];
			sceneContext.minimapTileModelColors = new int[MAX_Z][EXTENDED_SCENE_SIZE][EXTENDED_SCENE_SIZE][6][6];
		}

		for (int z = 0; z < MAX_Z; ++z) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; ++x) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; ++y) {
					Tile tile = sceneContext.scene.getExtendedTiles()[z][x][y];
					if (tile == null)
						continue;

					var paint = tile.getSceneTilePaint();
					if (paint != null) {
						int swColor = sceneContext.minimapTilePaintColors[z][x][y][0];
						int seColor = sceneContext.minimapTilePaintColors[z][x][y][1];
						int nwColor = sceneContext.minimapTilePaintColors[z][x][y][2];
						int neColor = sceneContext.minimapTilePaintColors[z][x][y][3];

						int swTexture = sceneContext.minimapTilePaintColors[z][x][y][4];
						int seTexture = sceneContext.minimapTilePaintColors[z][x][y][5];
						int nwTexture = sceneContext.minimapTilePaintColors[z][x][y][6];
						int neTexture = sceneContext.minimapTilePaintColors[z][x][y][7];

						swColor = environmentalLighting(swColor);
						seColor = environmentalLighting(seColor);
						nwColor = environmentalLighting(nwColor);
						neColor = environmentalLighting(neColor);
						swTexture = environmentalLighting(swTexture);
						seTexture = environmentalLighting(seTexture);
						nwTexture = environmentalLighting(nwTexture);
						neTexture = environmentalLighting(neTexture);

						seColor = swColor & 0xFF80 | seColor & 0x7F;
						nwColor = swColor & 0xFF80 | nwColor & 0x7F;
						neColor = swColor & 0xFF80 | neColor & 0x7F;

						seTexture = swTexture & 0xFF80 | seTexture & 0x7F;
						nwTexture = swTexture & 0xFF80 | nwTexture & 0x7F;
						neTexture = swTexture & 0xFF80 | neTexture & 0x7F;

						minimapTilePaintColorsLighting[z][x][y][0] = swColor;
						minimapTilePaintColorsLighting[z][x][y][1] = seColor;
						minimapTilePaintColorsLighting[z][x][y][2] = nwColor;
						minimapTilePaintColorsLighting[z][x][y][3] = neColor;

						minimapTilePaintColorsLighting[z][x][y][4] = swTexture;
						minimapTilePaintColorsLighting[z][x][y][5] = seTexture;
						minimapTilePaintColorsLighting[z][x][y][6] = nwTexture;
						minimapTilePaintColorsLighting[z][x][y][7] = neTexture;
					}

					var model = tile.getSceneTileModel();
					if (model != null) {
						final int faceCount = model.getFaceX().length;
						for (int face = 0; face < faceCount; ++face) {
							int c1 = sceneContext.minimapTileModelColors[z][x][y][face][0];
							int c2 = sceneContext.minimapTileModelColors[z][x][y][face][1];
							int c3 = sceneContext.minimapTileModelColors[z][x][y][face][2];

							int mc1 = sceneContext.minimapTileModelColors[z][x][y][face][3];
							int mc2 = sceneContext.minimapTileModelColors[z][x][y][face][4];
							int mc3 = sceneContext.minimapTileModelColors[z][x][y][face][5];

							c1 = environmentalLighting(c1);
							c2 = environmentalLighting(c2);
							c3 = environmentalLighting(c3);
							mc1 = environmentalLighting(mc1);
							mc2 = environmentalLighting(mc2);
							mc3 = environmentalLighting(mc3);

							c2 = c1 & 0xFF80 | c2 & 0x7F;
							c3 = c1 & 0xFF80 | c3 & 0x7F;

							mc2 = mc1 & 0xFF80 | mc2 & 0x7F;
							mc3 = mc1 & 0xFF80 | mc3 & 0x7F;

							minimapTileModelColorsLighting[z][x][y][face][0] = c1;
							minimapTileModelColorsLighting[z][x][y][face][1] = c2;
							minimapTileModelColorsLighting[z][x][y][face][2] = c3;

							minimapTileModelColorsLighting[z][x][y][face][3] = mc1;
							minimapTileModelColorsLighting[z][x][y][face][4] = mc2;
							minimapTileModelColorsLighting[z][x][y][face][5] = mc3;

						}
					}
				}
			}
		}


		sendApiMessage(minimapTilePaintColorsLighting,minimapTileModelColorsLighting);

		updateMinimapLighting = false;
	}

	public void sendApiMessage(int[][][][] minimapTilePaintColorsLighting, int[][][][][] minimapTileModelColorsLighting) {
		if (!rlhdAPI.isSubscribed(RLHDEvent.EVENT_MINIMAP)) {
			return;
		}

		Map<String, Object> payload = new HashMap<>();
		payload.put("paintColors", minimapTilePaintColorsLighting);
		payload.put("modelColors", minimapTileModelColorsLighting);
		eventBus.post(new PluginMessage("117hd", RLHDEvent.EVENT_MINIMAP.getEventName(), payload));
	}

	@Subscribe
	public void onRLHDSubscribe(RLHDSubscribe event) {
		if (event.getEvent() == RLHDEvent.EVENT_MINIMAP) {
			clientThread.invoke(() -> {
				startUp();
				SceneContext sceneContext = plugin.getSceneContext();
				if (sceneContext != null) {
					prepareScene(sceneContext);
				}
				updateMinimapLighting = true;
			});
		}
	}

	@Subscribe
	public void onRLHDUnsubscribe(RLHDUnsubscribe event) {
		if (event.getEvent() == RLHDEvent.EVENT_MINIMAP) {
			SceneContext sceneContext = plugin.getSceneContext();
			if (sceneContext != null) {
				clear(sceneContext);
			}
		}
	}

	private int environmentalLighting(int packedHsl) {
		var rgb = ColorUtils.srgbToLinear(ColorUtils.packedHslToSrgb(packedHsl));
		for (int i = 0; i < 3; i++) {
			rgb[i] *=
				environmentManager.currentAmbientColor[i] * environmentManager.currentAmbientStrength +
				environmentManager.currentDirectionalColor[i] * environmentManager.currentDirectionalStrength;
			rgb[i] = clamp(rgb[i], 0, 1);
		}
		return ColorUtils.srgbToPackedHsl(ColorUtils.linearToSrgb(rgb));
	}

}
