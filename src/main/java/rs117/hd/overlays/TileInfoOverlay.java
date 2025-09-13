package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.tuple.Pair;
import rs117.hd.HdPlugin;
import rs117.hd.data.ObjectType;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.GamevalManager;
import rs117.hd.scene.MaterialManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.SceneUploader;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.scene.materials.Material;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.*;
import static rs117.hd.HdPlugin.ORTHOGRAPHIC_ZOOM;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.HIDDEN_HSL;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class TileInfoOverlay extends Overlay implements MouseListener, MouseWheelListener {
	private static final Font MONOSPACE_FONT = new Font("Courier New", Font.PLAIN, 12);
	private static final Color BACKDROP_COLOR = new Color(0, 0, 0, 100);
	private static final Color TRANSPARENT_YELLOW_50 = new Color(255, 255, 0, 50);
	private static final Color TRANSPARENT_YELLOW_100 = new Color(255, 255, 0, 100);
	private static final Color TRANSPARENT_WHITE_100 = new Color(255, 255, 255, 100);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	private MaterialManager materialManager;

	@Inject
	private TileOverrideManager tileOverrideManager;

	@Inject
	private GamevalManager gamevalManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Getter
	private boolean active;

	private float[] mousePos;
	private boolean ctrlHeld;
	private boolean ctrlToggled;
	private boolean shiftHeld;
	private boolean altHeld;
	private float zoom = 1;

	private static final int MODE_TILE_INFO = 0;
	private static final int MODE_MODEL_INFO = 1;
	private static final int MODE_SCENE_AABBS = 2;
	private static final int MODE_OBJECT_IDS = 3;

	private int mode;
	private int aabbMarkingStage;
	private AABB pendingSelection;
	private final ArrayList<AABB> selections = new ArrayList<>();
	private final int[] selectedAreaAabb = { -1, 0 };
	private final int[] hoveredAreaAabb = { -1, 0 };
	private final int[][] markedWorldPoints = new int[2][3];
	private int[] hoveredWorldPoint = new int[3];
	private int targetPlane = MAX_Z - 1;
	private boolean selectionIncludeZ;

	private SceneContext currentSceneContext;
	private Area[] visibleAreas = new Area[0];
	private final AABB dummyAabb = new AABB(0, 0);
	private int[] sceneBase;
	private final ArrayList<String> hoveredGamevals = new ArrayList<>();
	private int hoveredGamevalsIndex;
	private int hoveredGamevalsHash;
	private int copiedGamevalsHash;

	public TileInfoOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean activate) {
		this.active = activate;
		if (activate) {
			overlayManager.add(this);
			// Listen to events before they're possibly consumed in DeveloperTools
			mouseManager.registerMouseListener(0, this);
			mouseManager.registerMouseWheelListener(this);
		} else {
			overlayManager.remove(this);
			mouseManager.unregisterMouseListener(this);
			mouseManager.unregisterMouseWheelListener(this);
		}
		tileOverrideManager.setTrackReplacements(activate);
	}

	@Override
	public synchronized Dimension render(Graphics2D g) {
		// Disable the overlay while loading a scene, since tile overrides aren't thread safe
		if (plugin.isLoadingScene())
			return null;

		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return null;

		if (sceneContext != currentSceneContext) {
			currentSceneContext = sceneContext;

			hoveredAreaAabb[0] = -1;
			hoveredAreaAabb[1] = 0;
			copyTo(selectedAreaAabb, hoveredAreaAabb);

			sceneBase = Objects.requireNonNullElseGet(
				sceneContext.sceneBase,
				() -> HDUtils.getSceneBaseBestGuess(sceneContext.scene, client.getPlane())
			);

			if (sceneContext.sceneBase == null) {
				visibleAreas = new Area[0];
			} else {
				visibleAreas = Arrays
					.stream(AreaManager.AREAS)
					.map(area -> {
						var copy = new Area(area.name);
						copy.regions = area.regions;
						copy.regionBoxes = area.regionBoxes;
						copy.rawAabbs = area.rawAabbs;
						copy.normalize();
						copy.unhideAreas = area.unhideAreas;
						copy.aabbs = Arrays
							.stream(copy.aabbs)
							.map(aabb -> sceneContext.sceneBounds.intersects(aabb) ? aabb : dummyAabb)
							.toArray(AABB[]::new);
						return copy;
					})
					.filter(area -> Arrays.stream(area.aabbs)
						.anyMatch(aabb -> aabb != dummyAabb))
					.toArray(Area[]::new);
			}
		}

		boolean ctrlPressed = client.isKeyPressed(KeyCode.KC_CONTROL);
		if (ctrlHeld != ctrlPressed) {
			ctrlHeld = ctrlPressed;
			if (ctrlPressed)
				ctrlToggled = !ctrlToggled;
		}
		boolean shiftPressed = client.isKeyPressed(KeyCode.KC_SHIFT);
		if (shiftHeld != shiftPressed) {
			shiftHeld = shiftPressed;
			if (shiftPressed)
				mode = (mode + 1) % 4;
		}
		altHeld = client.isKeyPressed(KeyCode.KC_ALT);

		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		int[][][] templateChunks = sceneContext.scene.isInstance() ? sceneContext.scene.getInstanceTemplateChunks() : null;
		Point canvasMousePos = client.getMouseCanvasPosition();
		mousePos = null;
		if (canvasMousePos != null && canvasMousePos.getX() != -1 && canvasMousePos.getY() != -1)
			mousePos = new float[] { canvasMousePos.getX(), canvasMousePos.getY() };
		hoveredGamevals.clear();

		int maxPlane = client.getPlane();
		int minPlane = 0;
		if (ctrlHeld)
			minPlane = maxPlane = targetPlane;

		if (mousePos != null) {
			g.setFont(FontManager.getRunescapeFont());
			g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

			tileLoop:
			for (int secondTry = 0; secondTry <= 1; secondTry++) {
				for (int z = maxPlane; z >= minPlane; z--) {
					for (int isBridge = 1; isBridge >= 0; isBridge--) {
						for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
							for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
								Tile tile = tiles[z][x][y];
								boolean shouldDraw = tile != null && (isBridge == 0 || tile.getBridge() != null);
								if (shouldDraw) {
									if (templateChunks != null) {
										int sx = x - SCENE_OFFSET;
										int sy = y - SCENE_OFFSET;
										if (sx < 0 || sy < 0 || sx >= SCENE_SIZE || sy >= SCENE_SIZE)
											continue;
										int chunk = templateChunks[z][sx / CHUNK_SIZE][sy / CHUNK_SIZE];
										if (chunk == -1 && !ctrlHeld)
											continue;
									}

									if (secondTry == 0) {
										var paint = tile.getSceneTilePaint();
										if ((paint == null || paint.getNeColor() == HIDDEN_HSL) && tile.getSceneTileModel() == null)
											continue;
									}

									if (mode == MODE_TILE_INFO || mode == MODE_MODEL_INFO) {
										if (!drawTileInfo(g, sceneContext, tile))
											continue;
									} else {
										if (altHeld) {
											g.setColor(Color.YELLOW);
										} else {
											g.setColor(Color.CYAN);
											if (isBridge == 1 && tile.getBridge() != null) {
												g.setColor(Color.MAGENTA);
												tile = tile.getBridge();
											}
										}
										var poly = getCanvasTilePoly(client, sceneContext.scene, tile);
										if (poly == null || !poly.contains(mousePos[0], mousePos[1]))
											continue;
										g.drawPolygon(poly);
									}

									int tileZ = tile.getRenderLevel();
									hoveredWorldPoint = sceneContext.extendedSceneToWorld(x, y, tileZ);

									break tileLoop;
								}
							}
						}
					}
				}
			}
		}

		hoveredGamevalsHash = hoveredGamevals.hashCode();

		switch (mode) {
			case MODE_OBJECT_IDS:
				drawAllIds(g, sceneContext);
				break;
			case MODE_SCENE_AABBS:
				g.setFont(FontManager.getRunescapeSmallFont());

				drawLoadingLines(g);
				drawRegionBoxes(g, sceneContext);

				if (mousePos != null) {
					hoveredAreaAabb[0] = -1;
					hoveredAreaAabb[1] = 0;
					float[] v = new float[2];
					outer:
					for (int i = 0; i < visibleAreas.length; i++) {
						var area = visibleAreas[i];
						for (int j = 0; j < area.aabbs.length; j++) {
							if (i == selectedAreaAabb[0] && j == selectedAreaAabb[1])
								continue;

							var aabb = toLocalAabb(sceneContext, cropAabb(sceneContext, area.aabbs[j]));
							var p = getAabbCanvasCenter(aabb);
							if (p != null) {
								subtract(v, mousePos, p);
								if (dot(v) < 26 * 26) {
									hoveredAreaAabb[0] = i;
									hoveredAreaAabb[1] = j;
									break outer;
								}
							}
						}
					}
				}

				for (int i = 0; i < visibleAreas.length; i++) {
					var area = visibleAreas[i];
					if (area.name.equals("ALL"))
						continue;

					boolean areaHovered = i == hoveredAreaAabb[0];
					boolean areaSelected = i == selectedAreaAabb[0];
					for (int j = 0; j < area.aabbs.length; j++) {
						AABB aabb = area.aabbs[j];
						if (aabb == dummyAabb)
							continue;

						boolean hovered = areaHovered && j == hoveredAreaAabb[1];
						boolean selected = areaSelected && j == selectedAreaAabb[1];
						if (hovered || selected)
							continue;

						String label = aabb.toArgs();
						if (aabb.isVolume())
							label = area.name + "[" + j + "]\n" + label;
						if (sceneContext.currentArea != null && sceneContext.currentArea.name.equals(area.name))
							label = "CURRENT\n" + label;

						// Since we have a bunch of AABBs spanning all planes,
						// it would be a bit obnoxious to always render the full AABB
						AABB croppedAabb = cropAabb(sceneContext, aabb);
						var localAabb = toLocalAabb(sceneContext, croppedAabb);

						g.setColor(TRANSPARENT_WHITE_100);
						drawLocalAabb(g, localAabb);

						g.setColor(Color.LIGHT_GRAY);
						drawLocalAabbLabel(g, localAabb, label, false);
					}

					for (int j = 0; j < area.unhideAreas.length; j++) {
						AABB aabb = area.unhideAreas[j];
						String label = aabb.toArgs();
						if (aabb.isVolume())
							label = area.name + ".unhide[" + j + "]\n" + label;
						if (sceneContext.currentArea != null && sceneContext.currentArea.name.equals(area.name))
							label = "CURRENT\n" + label;

						var localAabb = toLocalAabb(sceneContext, cropAabb(sceneContext, aabb));
						g.setColor(Color.PINK);
						drawLocalAabb(g, localAabb);
						drawLocalAabbLabel(g, localAabb, label, false);
					}
				}

				if (hoveredAreaAabb[0] != -1) {
					var area = visibleAreas[hoveredAreaAabb[0]];
					var aabb = area.aabbs[hoveredAreaAabb[1]];
					g.setColor(Color.WHITE);

					var localAabb = toLocalAabb(sceneContext, aabb);
					drawLocalAabb(g, localAabb);
					drawLocalAabbLabel(g, localAabb, area.name + "[" + hoveredAreaAabb[1] + "]\n" + aabb.toArgs(), false);
				}

				if (selectedAreaAabb[0] != -1) {
					var area = visibleAreas[selectedAreaAabb[0]];
					var aabb = area.aabbs[selectedAreaAabb[1]];
					g.setColor(Color.CYAN);

					var localAabb = toLocalAabb(sceneContext, aabb);
					drawLocalAabb(g, localAabb);
					drawLocalAabbLabel(g, localAabb, area.name + "[" + selectedAreaAabb[1] + "]\n" + aabb.toArgs(), true);
				}

				break;
		}

		// Update second selection point each frame
		if (aabbMarkingStage == 1) {
			System.arraycopy(hoveredWorldPoint, 0, markedWorldPoints[1], 0, 3);
			if (selectionIncludeZ || markedWorldPoints[0][2] != markedWorldPoints[1][2]) {
				pendingSelection = new AABB(markedWorldPoints[0], markedWorldPoints[1]);
			} else {
				pendingSelection = new AABB(
					markedWorldPoints[0][0],
					markedWorldPoints[0][1],
					markedWorldPoints[1][0],
					markedWorldPoints[1][1]
				);
			}
		}

		for (int i = 0; i < selections.size(); i++) {
			var aabb = selections.get(i);
			var localAabb = toLocalAabb(sceneContext, aabb);
			// Draw selection boxes
			g.setColor(Color.YELLOW);
			drawLocalAabb(g, localAabb);
			g.setFont(FontManager.getRunescapeFont());
			drawLocalAabbLabel(g, localAabb, "Selection[" + i + "]\n" + aabb.toArgs(), true);
		}

		if (pendingSelection != null) {
			var localAabb = toLocalAabb(sceneContext, pendingSelection);
			// Draw current selection box
			g.setColor(Color.YELLOW);
			drawLocalAabb(g, localAabb);
			g.setFont(FontManager.getRunescapeFont());
			drawLocalAabbLabel(g, localAabb, "Selection[" + selections.size() + "]\n" + pendingSelection.toArgs(), true);
		}

		if (sceneContext.sceneBase == null) {
			g.setColor(Color.RED);
			g.setFont(FontManager.getRunescapeFont());
			var b = g.getClipBounds();
			var str = "This is a non-contiguous instance. AABBs may not work.";
			int w = g.getFontMetrics().stringWidth(str);
			g.drawString(str, (int) (b.x + b.getWidth() / 2 - w / 2.f), 16);
		}

		return null;
	}

	private boolean drawTileInfo(Graphics2D g, SceneContext sceneContext, Tile tile) {
		boolean infoDrawn = false;

		if (tile != null) {
			Rectangle rect = null;
			Polygon poly;

			Tile bridge = tile.getBridge();
			if (bridge != null) {
				poly = getCanvasTilePoly(client, sceneContext.scene, bridge);
				if (poly != null && poly.contains(mousePos[0], mousePos[1])) {
					rect = drawTileInfo(g, sceneContext, bridge, poly, null);
					infoDrawn = true;
				}
			}

			poly = getCanvasTilePoly(client, sceneContext.scene, tile);
			if (poly != null && poly.contains(mousePos[0], mousePos[1])) {
				drawTileInfo(g, sceneContext, tile, poly, rect);
				infoDrawn = true;
			}
		}

		return infoDrawn;
	}

	private Rectangle drawTileInfo(Graphics2D g, SceneContext sceneContext, Tile tile, Polygon poly, Rectangle dodgeRect)
	{
		SceneTilePaint tilePaint = tile.getSceneTilePaint();
		SceneTileModel tileModel = tile.getSceneTileModel();

		Scene scene = sceneContext.scene;
		int tileX = tile.getSceneLocation().getX();
		int tileY = tile.getSceneLocation().getY();
		int tileZ = tile.getRenderLevel();
		int tileExX = tileX + SCENE_OFFSET;
		int tileExY = tileY + SCENE_OFFSET;
		int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);

		ArrayList<String> lines = new ArrayList<>();

		Color polyColor = Color.LIGHT_GRAY;
		if (mode == MODE_TILE_INFO) {
			sceneContext.tileOverrideVars.setTile(tile);
			if (tile.getBridge() != null)
				lines.add("Bridge");

			lines.add("Scene point: " + tileX + ", " + tileY + ", " + tileZ);
			lines.add("World point: " + Arrays.toString(worldPos));
			lines.add(String.format(
				"Region ID: %d (%d, %d)",
				HDUtils.worldToRegionID(worldPos),
				worldPos[0] >> 6,
				worldPos[1] >> 6
			));

			for (var environment : sceneContext.environments) {
				if (environment.area.containsPoint(worldPos)) {
					lines.add("Environment: " + environment);
					break;
				}
			}

			int overlayId = scene.getOverlayIds()[tileZ][tileExX][tileExY];
			var overlay = tileOverrideManager.getOverrideBeforeReplacements(worldPos, OVERLAY_FLAG | overlayId);
			var replacementPath = new StringBuilder(overlay.toString());
			while (true) {
				var replacement = overlay.resolveNextReplacement(sceneContext.tileOverrideVars);
				if (replacement == overlay)
					break;
				replacementPath.append("\n\t⤷ ").append(replacement);
				overlay = replacement;
			}
			lines.add(String.format("Overlay: ID %d -> %s", overlayId, replacementPath));
			lines.add(String.format(
				"GroundMaterial: %s -> %s",
				overlay.groundMaterial,
				overlay.groundMaterial.getRandomMaterial(worldPos)
			));

			int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];
			var underlay = tileOverrideManager.getOverrideBeforeReplacements(worldPos, underlayId);
			replacementPath = new StringBuilder(underlay.toString());
			while (true) {
				var replacement = underlay.resolveNextReplacement(sceneContext.tileOverrideVars);
				if (replacement == underlay)
					break;
				replacementPath.append("\n\t⤷ ").append(replacement);
				underlay = replacement;
			}
			lines.add(String.format("Underlay: ID %d -> %s", underlayId, replacementPath));
			lines.add(String.format(
				"GroundMaterial: %s -> %s",
				underlay.groundMaterial,
				underlay.groundMaterial.getRandomMaterial(worldPos)
			));

			if (tilePaint != null) {
				polyColor = client.isKeyPressed(KeyCode.KC_ALT) ? Color.YELLOW : Color.CYAN;
				lines.add("Tile type: Paint");
				Material material = materialManager.fromVanillaTexture(tilePaint.getTexture());
				lines.add(String.format("Material: %s (%d)", material.name, tilePaint.getTexture()));
				lines.add(String.format("HSL: %s", hslString(tile)));

				var override = tileOverrideManager.getOverride(sceneContext, tile, worldPos, OVERLAY_FLAG | overlayId, underlayId);
				lines.add("WaterType: " + proceduralGenerator.seasonalWaterType(override, tilePaint.getTexture()));
			} else if (tileModel != null) {
				polyColor = Color.ORANGE;
				lines.add("Tile type: Model");
				lines.add(String.format("Face count: %d", tileModel.getFaceX().length));

				HashSet<String> uniqueMaterials = new HashSet<>();
				int numChars = 0;
				if (tileModel.getTriangleTextureId() != null) {
					for (int texture : tileModel.getTriangleTextureId()) {
						String material = String.format("%s (%d)", materialManager.fromVanillaTexture(texture).name, texture);
						boolean unique = uniqueMaterials.add(material);
						if (unique) {
							numChars += material.length();
						}
					}
				}

				ArrayList<String> materials = new ArrayList<>(uniqueMaterials);
				Collections.sort(materials);

				if (materials.size() <= 1 || numChars < 26) {
					StringBuilder sb = new StringBuilder("Materials: { ");
					if (materials.isEmpty()) {
						sb.append("null");
					} else {
						String prefix = "";
						for (String m : materials) {
							sb.append(prefix).append(m);
							prefix = ", ";
						}
					}
					sb.append(" }");
					lines.add(sb.toString());
				} else {
					Iterator<String> iter = materials.iterator();
					lines.add("Materials: { " + iter.next() + ",");
					while (iter.hasNext()) {
						lines.add("\t  " + iter.next() + (iter.hasNext() ? "," : " }"));
					}
				}

				lines.add(String.format("HSL: %s", hslString(tile)));
			}

			sceneContext.tileOverrideVars.setTile(null); // Avoid accidentally keeping the old scene in memory
		}

		var decorObject = tile.getDecorativeObject();
		if (decorObject != null) {
			lines.add(String.format(
				"Decor Object: %s preori=%d ori=%d offset=[%d, %d] type=%s %s",
				getIdAndImpostorId(decorObject, decorObject.getRenderable()),
				HDUtils.getModelPreOrientation(decorObject.getConfig()),
				HDUtils.getModelOrientation(decorObject.getConfig()),
				decorObject.getXOffset(),
				decorObject.getYOffset(),
				ObjectType.fromConfig(decorObject.getConfig()),
				getModelInfo(decorObject.getRenderable())
			));
			lines.add("Decor Type: " + ObjectType.fromConfig(decorObject.getConfig()));
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null) {
			lines.add(String.format(
				"Ground Object: %s preori=%d ori=%d%s",
				getIdAndImpostorId(groundObject, groundObject.getRenderable()),
				HDUtils.getModelPreOrientation(groundObject.getConfig()),
				HDUtils.getModelOrientation(groundObject.getConfig()),
				getModelInfo(groundObject.getRenderable())
			));
			lines.add("Ground Type: " + ObjectType.fromConfig(groundObject.getConfig()));
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null) {
			if (wallObject.getRenderable1() != null) {
				lines.add(String.format(
					"Wall Object 1: %s bakedOri=%d ori=%d wallori=%d%s",
					getIdAndImpostorId(wallObject, wallObject.getRenderable1()),
					HDUtils.getModelPreOrientation(wallObject.getConfig()),
					HDUtils.getModelOrientation(wallObject.getConfig()),
					wallObject.getOrientationA(),
					getModelInfo(wallObject.getRenderable1())
				));
			}
			if (wallObject.getRenderable2() != null) {
				lines.add(String.format(
					"Wall Object 2: %s bakedOri=%d ori=%d wallori=%d%s",
					getIdAndImpostorId(wallObject, wallObject.getRenderable2()),
					HDUtils.getModelPreOrientation(wallObject.getConfig()),
					HDUtils.getModelOrientation(wallObject.getConfig()),
					wallObject.getOrientationB(),
					getModelInfo(wallObject.getRenderable2())
				));
			}
			lines.add("Wall Type: " + ObjectType.fromConfig(wallObject.getConfig()));
		}

		GameObject[] gameObjects = tile.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null)
				continue;
			int height = -1;
			int animationId = -1;
			int faceCount = 0;
			String id = "";
			var renderable = gameObject.getRenderable();
			if (renderable != null) {
				Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
				if (model != null)
					faceCount = model.getFaceCount();

				if (renderable instanceof NPC)
					continue;

				height = renderable.getModelHeight();

				if (renderable instanceof Player) {
					id = "name=" + ((Player) renderable).getName();
				} else {
					id = getIdAndImpostorId(gameObject, renderable);
					if (renderable instanceof DynamicObject) {
						var anim = ((DynamicObject) renderable).getAnimation();
						if (anim != null)
							animationId = anim.getId();
					}
				}
				id += " ";
			}

			lines.add(String.format(
				"%s: %spreori=%d ori=%d objori=%d height=%d anim=%d faces=%d%s",
				ModelHash.getTypeName(ModelHash.getType(gameObject.getHash())),
				id,
				HDUtils.getModelPreOrientation(gameObject.getConfig()),
				HDUtils.getModelOrientation(gameObject.getConfig()),
				gameObject.getModelOrientation(),
				height,
				animationId,
				faceCount,
				getModelInfo(renderable)
			));
			lines.add("Object Type: " + ObjectType.fromConfig(gameObject.getConfig()));
		}

		for (var npc : client.getTopLevelWorldView().npcs()) {
			var lp = npc.getLocalLocation();
			int size = npc.getComposition().getSize() / 2;
			int x = lp.getSceneX();
			int y = lp.getSceneY();
			if (x - size <= tileX && tileX <= x + size && y - size <= tileY && tileY <= y + size) {
				var name = gamevalManager.getNpcName(npc.getId());
				hoveredGamevals.add(name);
				lines.add(String.format(
					"NPC: %s (%d) name=%s ori=[%d,%d] anim=%d impostor=?%s",
					name,
					npc.getId(),
					npc.getName(),
					npc.getOrientation(),
					npc.getCurrentOrientation(),
					npc.getAnimation(),
					getModelInfo(npc)
				));
			}
		}

		for (GraphicsObject graphicsObject : client.getGraphicsObjects()) {
			var lp = graphicsObject.getLocation();
			if (lp.getSceneX() == tileX && lp.getSceneY() == tileY) {
				var name = gamevalManager.getSpotanimName(graphicsObject.getId());
				hoveredGamevals.add(name);
				lines.add(String.format(
					"Graphics Object: %s (%d)%s",
					name,
					graphicsObject.getId(),
					getModelInfo(graphicsObject)
				));
			}
		}

		if (tile.getBridge() != null)
			polyColor = Color.MAGENTA;
		g.setColor(polyColor);
		g.drawPolygon(poly);

		if (lines.isEmpty())
			return null;

		for (int i = 0; i < lines.size(); i++) {
			var moreLines = lines.get(i).split("\\n");
			if (moreLines.length > 1) {
				//noinspection SuspiciousListRemoveInLoop
				lines.remove(i);
				lines.addAll(i, List.of(moreLines));
			}
		}

		int padding = 4;
		int xPadding = padding * 2;
		FontMetrics fm = g.getFontMetrics();
		int lineHeight = fm.getHeight();
		int totalHeight = lineHeight * lines.size() + padding * 3;
		int space = fm.stringWidth(": ");
		int indent = fm.stringWidth("{ ");

		int leftWidth = 0;
		int rightWidth = 0;

		Function<String, Pair<String, String>> splitter = line ->
		{
			int i = line.indexOf(":");
			String left = line;
			String right = "";
			if (left.startsWith("\t"))
			{
				right = left;
				left = "";
			} else if (i != -1)
			{
				left = line.substring(0, i);
				right = line.substring(i + 1);
			}

			return Pair.of(left, right);
		};

		Font f = g.getFont();
		for (String line : lines)
		{
			Pair<String, String> pair = splitter.apply(line);
			if (pair.getRight().isEmpty())
			{
				int halfWidth = fm.stringWidth(Text.removeTags(pair.getLeft())) / 2;
				leftWidth = max(leftWidth, halfWidth);
				rightWidth = max(rightWidth, halfWidth);
			}
			else
			{
				leftWidth = max(leftWidth, fm.stringWidth(Text.removeTags(pair.getLeft())));
				var rfm = fm;
				if (pair.getRight().contains("<tt>"))
					rfm = g.getFontMetrics(MONOSPACE_FONT);
				rightWidth = max(rightWidth, rfm.stringWidth(Text.removeTags(pair.getRight())));
			}
		}

		Rectangle2D polyBounds = poly.getBounds2D();
		float centerX = (float) polyBounds.getCenterX();
		float centerY = (float) (polyBounds.getCenterY() - polyBounds.getHeight() / 2.f);

		var bounds = g.getClipBounds();

		int totalWidth = leftWidth + rightWidth + space + xPadding * 2;
		Rectangle rect = new Rectangle(
			(int) clamp(centerX - totalWidth / 2.f, bounds.x, bounds.x + bounds.width - totalWidth),
			(int) clamp(centerY - totalHeight - padding, bounds.y + 16, bounds.y + bounds.height - totalHeight),
			totalWidth,
			totalHeight
		);
		if (dodgeRect != null && dodgeRect.intersects(rect))
		{
			// Avoid overlapping with other tile info
			rect.y = dodgeRect.y - rect.height - padding;
		}

		g.setColor(BACKDROP_COLOR);
		g.fillRect(rect.x, rect.y, rect.width, rect.height);

		g.setColor(Color.WHITE);

		int offsetY = 0;
		for (String line : lines)
		{
			boolean dropShadow = true;

			Pair<String, String> pair = splitter.apply(line);
			offsetY += lineHeight;
			float x, y;
			if (pair.getRight().isEmpty())
			{
				// centered
				x = rect.x + rect.width / 2.f - fm.stringWidth(pair.getLeft()) / 2.f;
				y = rect.y + padding + offsetY;
			}
			else
			{
				boolean indented = pair.getRight().startsWith("\t");
				if (pair.getRight().contains("<tt>")) {
					g.setFont(MONOSPACE_FONT);
					dropShadow = false;
				}

				// left & right
				x = rect.x + xPadding + leftWidth - fm.stringWidth(pair.getLeft()) + (indented ? indent : 0);
				y = rect.y + padding + offsetY;
			}

			drawString(g, line, (int) x, (int) y, dropShadow);

			g.setFont(f);
		}

		return rect;
	}

	private String getIdAndImpostorId(TileObject object, @Nullable Renderable renderable) {
		int id = object.getId();
		int impostorId = getIdOrImpostorId(object, renderable);
		String name = gamevalManager.getObjectName(id);
		if (id == impostorId) {
			hoveredGamevals.add(name);
			return String.format("%s (%d)", name, id);
		}

		String impostorName = gamevalManager.getObjectName(impostorId);
		hoveredGamevals.add(impostorName);
		return String.format("%s (%d) -> %s (%d)", name, id, impostorName, impostorId);
	}

	private int getIdOrImpostorId(TileObject object, @Nullable Renderable renderable) {
		return ModelHash.getUuidId(ModelHash.generateUuid(client, object.getHash(), renderable));
	}

	private String getModelInfo(Renderable renderable) {
		if (renderable == null)
			return " null renderable";

		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null)
			return " null model";

		switch (mode) {
			case MODE_TILE_INFO:
				return
					"  " + (
						renderable instanceof Model ? "<col=#00ff00>static</col>" :
						(renderable instanceof DynamicObject || renderable instanceof Actor) ?
							"<col=#ff0000>dynamic</col>" : "<col=#ffff00>maybe dynamic</col>"
					) +
					"  scenebuf=" + (
						model.getSceneId() == SceneUploader.EXCLUDED_FROM_SCENE_BUFFER ? "excluded" :
							(model.getSceneId() & SceneUploader.SCENE_ID_MASK) == currentSceneContext.id ?
								model.getBufferOffset() : "dynamic"
					);
			case MODE_MODEL_INFO:
				int[] faceColors = model.getFaceColors1();
				byte[] faceTransparencies = model.getFaceTransparencies();
				int[] faceAhsl = new int[model.getFaceCount()];
				for (int i = 0; i < faceAhsl.length; i++)
					faceAhsl[i] = (faceTransparencies == null ? 0xFF : 0xFF - (faceTransparencies[i] & 0xFF)) << 16 | faceColors[i];
				var colors = Arrays.stream(faceAhsl)
					.distinct()
					.sorted()
					.mapToObj(ahsl -> {
						var hsl = ColorUtils.unpackRawHsl(ahsl);
						var alpha = ahsl >> 16;
						return String.format(
							"<col=%s>%5d [%3d %2d %1d %3d]</col>",
							String.format("#%08x", ColorUtils.packSrgb(ColorUtils.packedHslToSrgb(ahsl)) << 8 | alpha),
							ahsl & 0xFFFF, alpha, hsl[0], hsl[1], hsl[2]
						);
					})
					.toArray(String[]::new);

				int columns = clamp(round(sqrt(colors.length / 5f)), 3, 8);
				int rows = ceil(colors.length / (float) columns);

				StringBuilder str = new StringBuilder("\nFace colors: ").append(colors.length);
				for (int i = 0; i < rows; i++) {
					str.append("\n\t<tt>");
					for (int j = 0; j < columns; j++) {
						int idx = i * columns + j;
						if (idx < colors.length)
							str.append(colors[idx]);
					}
				}

				if (model.getFaceTextures() != null) {
					var textureIds = new HashSet<Integer>();
					for (int textureId : model.getFaceTextures())
						textureIds.add(textureId);
					textureIds.remove(-1);
					if (!textureIds.isEmpty()) {
						str.append("\nTexture IDs: [ ");
						String prefix = "";
						for (int id : textureIds) {
							str.append(prefix).append(id);
							prefix = ", ";
						}
						str.append("]");
					}
				}

				return str.toString();
		}

		return "";
	}

	public Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, Tile tile) {
		if (tile == null)
			return null;
		var l = tile.getSceneLocation();
		return getCanvasTilePoly(client, scene, l.getX(), l.getY(), tile.getPlane());
	}

	public Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, int... sceneXYplane) {
		final int wx = sceneXYplane[0] * LOCAL_TILE_SIZE;
		final int sy = sceneXYplane[1] * LOCAL_TILE_SIZE;
		final int ex = (sceneXYplane[0] + 1) * LOCAL_TILE_SIZE;
		final int ny = (sceneXYplane[1] + 1) * LOCAL_TILE_SIZE;

		final int sw = getHeight(scene, wx, sy, sceneXYplane[2]);
		final int se = getHeight(scene, ex, sy, sceneXYplane[2]);
		final int ne = getHeight(scene, ex, ny, sceneXYplane[2]);
		final int nw = getHeight(scene, wx, ny, sceneXYplane[2]);

		float[] p1 = localToCanvas(client, wx, sy, sw);
		float[] p2 = localToCanvas(client, ex, sy, se);
		float[] p3 = localToCanvas(client, ex, ny, ne);
		float[] p4 = localToCanvas(client, wx, ny, nw);
		if (p1 == null || p2 == null || p3 == null || p4 == null)
			return null;

		Polygon poly = new Polygon();
		poly.addPoint((int) p1[0], (int) p1[1]);
		poly.addPoint((int) p2[0], (int) p2[1]);
		poly.addPoint((int) p3[0], (int) p3[1]);
		poly.addPoint((int) p4[0], (int) p4[1]);

		return poly;
	}

	private static int getHeight(Scene scene, int localX, int localY, int plane) {
		int sceneExX = clamp((localX >> LOCAL_COORD_BITS) + SCENE_OFFSET, 0, EXTENDED_SCENE_SIZE - 1);
		int sceneExY = clamp((localY >> LOCAL_COORD_BITS) + SCENE_OFFSET, 0, EXTENDED_SCENE_SIZE - 1);

		int[][][] tileHeights = scene.getTileHeights();
		int x = localX & (LOCAL_TILE_SIZE - 1);
		int y = localY & (LOCAL_TILE_SIZE - 1);
		int var8 = x * tileHeights[plane][sceneExX + 1][sceneExY] +
				   (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneExX][sceneExY] >> LOCAL_COORD_BITS;
		int var9 = x * tileHeights[plane][sceneExX + 1][sceneExY + 1] +
				   (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneExX][sceneExY + 1] >> LOCAL_COORD_BITS;
		return y * var9 + (LOCAL_TILE_SIZE - y) * var8 >> 7;
	}

	private float[] localToCanvas(@Nonnull Client client, int x, int y, int z) {
		// Using floats to support coordinates much larger than normal local coordinates
		x -= client.getCameraX();
		y -= client.getCameraY();
		z -= client.getCameraZ();
		int cameraPitch = client.getCameraPitch();
		int cameraYaw = client.getCameraYaw();
		float pitchSin = sin(cameraPitch * JAU_TO_RAD);
		float pitchCos = cos(cameraPitch * JAU_TO_RAD);
		float yawSin = sin(cameraYaw * JAU_TO_RAD);
		float yawCos = cos(cameraYaw * JAU_TO_RAD);
		float x1 = x * yawCos + y * yawSin;
		float y1 = y * yawCos - x * yawSin;
		float y2 = z * pitchCos - y1 * pitchSin;
		float z1 = y1 * pitchCos + z * pitchSin;
		if (z1 >= 1) {
			float scale = client.getScale();
			float screenX = x1 * scale;
			float screenY = y2 * scale;
			if (plugin.orthographicProjection) {
				screenX *= ORTHOGRAPHIC_ZOOM;
				screenY *= ORTHOGRAPHIC_ZOOM;
			} else {
				screenX /= z1;
				screenY /= z1;
			}

			screenX += client.getViewportWidth() / 2.f;
			screenY += client.getViewportHeight() / 2.f;

			return new float[] {
				(screenX + client.getViewportXOffset()),
				(screenY + client.getViewportYOffset()),
				min(Integer.MAX_VALUE, z1)
			};
		}

		return null;
	}

	private static String hslString(Tile tile) {
		int[] hsl = new int[3];
		int rawHsl = HDUtils.getSouthWesternMostTileColor(hsl, tile);
		if (rawHsl == HIDDEN_HSL)
			return "HIDDEN";
		return rawHsl + " " + Arrays.toString(hsl);
	}

	private void drawAllIds(Graphics2D g, SceneContext ctx) {
		g.setFont(FontManager.getRunescapeSmallFont());
		g.setColor(new Color(255, 255, 255, 127));

		Tile[][][] tiles = ctx.scene.getExtendedTiles();
		int plane = ctrlHeld ? MAX_Z - 1 : client.getPlane();
		for (int z = plane; z >= 0; z--) {
			for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
				for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
					Tile tile = tiles[z][x][y];
					if (tile == null)
						continue;

					var lp = tile.getLocalLocation();
					int lines = 0;
					for (int isBridge = 1; isBridge >= 0; isBridge--) {
						var t = tile;
						if (isBridge == 1) {
							t = tile.getBridge();
							if (t == null)
								continue;
						}

						GroundObject groundObject = t.getGroundObject();
						if (groundObject != null)
							drawTileObjectInfo(g, lp, groundObject, groundObject.getRenderable(), lines++);

						WallObject wallObject = t.getWallObject();
						if (wallObject != null)
							drawTileObjectInfo(g, lp, wallObject, wallObject.getRenderable1(), lines++);

						for (GameObject gameObject : t.getGameObjects())
							if (gameObject != null)
								drawTileObjectInfo(g, lp, gameObject, gameObject.getRenderable(), lines++);
					}
				}
			}
		}
	}

	private void drawTileObjectInfo(Graphics2D g, LocalPoint lp, TileObject object, Renderable renderable, int line) {
		int type = ModelHash.getType(object.getHash());
		String str;
		if (zoom > 1.2f) {
			str = ModelHash.getTypeName(type) + ": " + getIdAndImpostorId(object, renderable);
		} else {
			str = ModelHash.getTypeNameShort(type) + ": " + getIdOrImpostorId(object, renderable);
		}
		var p = localToCanvas(client, lp.getX(), lp.getY(),
			Perspective.getTileHeight(client, lp, object.getPlane())
		);
		if (p == null)
			return;

		var fm = g.getFontMetrics();
		int w = fm.stringWidth(str);

		drawString(g, str, (int) (p[0] - w / 2.f), (int) (p[1] + line * fm.getHeight()), true);
	}

	private void drawString(Graphics2D g2d, String str, int x, int y, boolean dropShadow) {
		Color origColor = g2d.getColor();

		setAntiAliasing(g2d, false);

		String stripped = Text.removeTags(str);
		if (dropShadow) {
			g2d.setColor(Color.BLACK);
			g2d.drawString(stripped, x + 1, y + 1);
			g2d.setColor(origColor);
		}

		if (!str.contains("<col=")) {
			g2d.drawString(stripped, x, y);
			return;
		}

		var fm = g2d.getFontMetrics();
		final Pattern pattern = Pattern.compile("<col=#?([a-fA-F0-9]{3,8})>(.*?)</col>");
		var m = pattern.matcher(str);
		int end = 0;
		while (m.find()) {
			if (end < m.start()) {
				var s = Text.removeTags(str.substring(end, m.start()));
				g2d.setColor(origColor);
				g2d.drawString(s, x, y);
				x += fm.stringWidth(s);
			}
			end = m.end();

			var hex = m.group(1);
			int i = (int) Long.parseLong(hex, 16);
			int r, g, b, a = 0xFF;
			if (hex.length() == 3) {
				r = (i >>> 8) * 2;
				g = (i >>> 4 & 0xF) * 2;
				b = (i & 0xF) * 2;
			} else if (hex.length() == 6) {
				r = i >>> 16;
				g = i >>> 8 & 0xFF;
				b = i & 0xFF;
			} else if (hex.length() == 8) {
				r = i >>> 24;
				g = i >>> 16 & 0xFF;
				b = i >>> 8 & 0xFF;
				a = i & 0xFF;
			} else {
				g2d.drawString(m.group(0), x, y);
				x += fm.stringWidth(m.group(0));
				continue;
			}

			var withBrackets = m.group(2);
			var withoutBrackets = withBrackets.replaceAll("[\\[\\]]", " ");
			var onlyBrackets = withBrackets.replaceAll("[^\\[\\]]", " ");

			var c = new Color(r, g, b, a);
			g2d.setColor(c);
			int w = fm.stringWidth(withoutBrackets);
			int h = fm.getHeight();
			g2d.fillRect(x - 2, y - fm.getAscent() + fm.getLeading() - 2, w + 4, h + 2);
			g2d.setColor(getContrastColor(c));
			g2d.drawString(withoutBrackets, x, y);
			g2d.drawString(onlyBrackets, x, y - 1);

			x += fm.stringWidth(withoutBrackets);
		}

		g2d.setColor(origColor);
		if (end < str.length())
			g2d.drawString(str.substring(end), x, y);
	}

	private Color getContrastColor(Color color) {
		double y = (299. * color.getRed() + 587 * color.getGreen() + 114 * color.getBlue()) / 1000;
		return y >= 128 ? Color.black : Color.white;
	}

	/**
	 * Draw line given local scene coordinates
	 */
	private void drawLine(Graphics2D g, int x1, int y1, int z1, int x2, int y2, int z2) {
		// Using floats to support coordinates much larger than normal local coordinates
		float yawSin = sin(plugin.cameraOrientation[0]);
		float yawCos = cos(plugin.cameraOrientation[0]);
		float pitchSin = sin(plugin.cameraOrientation[1]);
		float pitchCos = cos(plugin.cameraOrientation[1]);

		x1 -= client.getCameraX();
		y1 -= client.getCameraY();
		z1 -= client.getCameraZ();
		x2 -= client.getCameraX();
		y2 -= client.getCameraY();
		z2 -= client.getCameraZ();

		float ax = x1 * yawCos + y1 * yawSin;
		float aUnpitchedZ = y1 * yawCos - x1 * yawSin;
		float ay = z1 * pitchCos - aUnpitchedZ * pitchSin;
		float az = aUnpitchedZ * pitchCos + z1 * pitchSin;

		float bx = x2 * yawCos + y2 * yawSin;
		float bUnpitchedZ = y2 * yawCos - x2 * yawSin;
		float by = z2 * pitchCos - bUnpitchedZ * pitchSin;
		float bz = bUnpitchedZ * pitchCos + z2 * pitchSin;

		if (!plugin.orthographicProjection) {
			// Project points which lie behind the camera onto the
			// near plane, so lines can still be drawn accurately

			// Both behind the near plane
			if (az < 1 && bz < 1)
				return;

			float vx = bx - ax;
			float vy = by - ay;
			float vz = bz - az;

			if (az < 1) {
				// A is behind the near plane
				// az + (bz - az) * t = 1
				// t = (1 - az) / (bz - az)
				double t = (1.f - az) / vz;
				ax += (float) (vx * t);
				ay += (float) (vy * t);
				az = 1;
			} else if (bz < 1) {
				// B is behind the near plane
				double t = (1.f - bz) / vz;
				bx += (float) (vx * t);
				by += (float) (vy * t);
				bz = 1;
			}
		}

		if (plugin.orthographicProjection) {
			ax *= ORTHOGRAPHIC_ZOOM;
			ay *= ORTHOGRAPHIC_ZOOM;
			bx *= ORTHOGRAPHIC_ZOOM;
			by *= ORTHOGRAPHIC_ZOOM;
		} else {
			ax /= az;
			ay /= az;
			bx /= bz;
			by /= bz;
		}

		int scale = client.getScale();
		ax *= scale;
		ay *= scale;
		bx *= scale;
		by *= scale;

		int w = client.getViewportWidth();
		int h = client.getViewportHeight();

		float offsetX = client.getViewportXOffset() + w / 2.f;
		float offsetY = client.getViewportYOffset() + h / 2.f;
		ax += offsetX;
		ay += offsetY;
		bx += offsetX;
		by += offsetY;

		float vx = bx - ax;
		float vy = by - ay;

		// a + v * t = edge
		// t = (edge - a) / v
		if (ax < 0) {
			ay += -ax / vx * vy;
			ax = 0;
		} else if (ax > w) {
			ay += (w - ax) / vx * vy;
			ax = w;
		}
		if (ay < 0) {
			ax += -ay / vy * vx;
			ay = 0;
		} else if (ay > h) {
			ax += (h - ay) / vy * vx;
			ay = h;
		}

		if (bx < 0) {
			by += -bx / vx * vy;
			bx = 0;
		} else if (bx > w) {
			by += (w - bx) / vx * vy;
			bx = w;
		}
		if (by < 0) {
			bx += -by / vy * vx;
			by = 0;
		} else if (by > h) {
			bx += (h - by) / vy * vx;
			by = h;
		}

		int fromX = round(ax);
		int fromY = round(ay);
		int toX = round(bx);
		int toY = round(by);

		if (fromX == toX && fromY == toY)
			return;

		g.drawLine(fromX, fromY, toX, toY);
	}

	private void setAntiAliasing(Graphics2D g, boolean state) {
		g.setRenderingHint(
			RenderingHints.KEY_TEXT_ANTIALIASING,
			state ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF
		);
	}

	private AABB toLocalAabb(SceneContext ctx, AABB aabb) {
		return toLocalAabb(ctx, aabb, 1);
	}

	private AABB toLocalAabb(SceneContext ctx, AABB aabb, float scale) {
		int x1 = (aabb.minX - sceneBase[0]) * LOCAL_TILE_SIZE;
		int y1 = (aabb.minY - sceneBase[1]) * LOCAL_TILE_SIZE;
		int x2 = (aabb.maxX + 1 - sceneBase[0]) * LOCAL_TILE_SIZE;
		int y2 = (aabb.maxY + 1 - sceneBase[1]) * LOCAL_TILE_SIZE;

		int minZ = 0, maxZ = MAX_Z;
		if (aabb.hasZ()) {
			minZ = clamp(aabb.minZ, 0, MAX_Z);
			maxZ = clamp(aabb.maxZ, 0, MAX_Z) + 1;
		}
		int z1 = Integer.MAX_VALUE;
		int z2 = Integer.MIN_VALUE;

		for (int i = minZ; i < maxZ; i++) {
			int sw = getHeight(ctx.scene, x1, y1, i);
			int nw = getHeight(ctx.scene, x1, y2, i);
			int ne = getHeight(ctx.scene, x2, y2, i);
			int se = getHeight(ctx.scene, x2, y1, i);
			if (sw != -1) {
				z1 = min(z1, sw);
				z2 = max(z2, sw);
			}
			if (nw != -1) {
				z1 = min(z1, nw);
				z2 = max(z2, nw);
			}
			if (ne != -1) {
				z1 = min(z1, ne);
				z2 = max(z2, ne);
			}
			if (se != -1) {
				z1 = min(z1, se);
				z2 = max(z2, se);
			}
		}

		if (scale == 1)
			return new AABB(x1, y1, z1, x2, y2, z2);

		float cx = (x1 + x2) / 2.f;
		float cy = (y1 + y2) / 2.f;
		float cz = (z1 + z2) / 2.f;
		int sx = (int) ((x2 - x1) / 2.f);
		int sy = (int) ((y2 - y1) / 2.f);
		int sz = (int) ((z2 - z1) / 2.f);

		return new AABB(
			round(cx - sx * scale),
			round(cy - sy * scale),
			round(cz - sz * scale),
			round(cx + sx * scale),
			round(cy + sy * scale),
			round(cz + sz * scale)
		);
	}

	private AABB cropAabb(SceneContext ctx, AABB aabb) {
		if (aabb.isPoint()) {
			int sceneExX = aabb.minX - (sceneBase[0] - SCENE_OFFSET);
			int sceneExY = aabb.minY - (sceneBase[1] - SCENE_OFFSET);
			if (sceneExX >= 0 && sceneExY >= 0 && sceneExX < EXTENDED_SCENE_SIZE && sceneExY < EXTENDED_SCENE_SIZE) {
				int minZ = MAX_Z - 1;
				int maxZ = 0;
				int filled = ctx.filledTiles[sceneExX][sceneExY];
				for (int plane = 0; plane < MAX_Z; plane++) {
					if ((filled & (1 << plane)) != 0) {
						minZ = min(minZ, plane);
						maxZ = max(maxZ, plane);
					}
				}
				return new AABB(aabb.minX, aabb.minY, minZ, aabb.minX, aabb.minY, maxZ);
			}
		}

		return aabb;
	}

	private void drawLocalAabb(Graphics2D g, AABB aabb) {
		setAntiAliasing(g, true);

		// Draw bottom rect
		drawLine(g, aabb.minX, aabb.minY, aabb.minZ, aabb.minX, aabb.maxY, aabb.minZ);
		drawLine(g, aabb.minX, aabb.maxY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.minZ);
		drawLine(g, aabb.maxX, aabb.maxY, aabb.minZ, aabb.maxX, aabb.minY, aabb.minZ);
		drawLine(g, aabb.maxX, aabb.minY, aabb.minZ, aabb.minX, aabb.minY, aabb.minZ);

		if (aabb.minZ != aabb.maxZ) {
			// Draw top rect
			drawLine(g, aabb.minX, aabb.minY, aabb.maxZ, aabb.minX, aabb.maxY, aabb.maxZ);
			drawLine(g, aabb.minX, aabb.maxY, aabb.maxZ, aabb.maxX, aabb.maxY, aabb.maxZ);
			drawLine(g, aabb.maxX, aabb.maxY, aabb.maxZ, aabb.maxX, aabb.minY, aabb.maxZ);
			drawLine(g, aabb.maxX, aabb.minY, aabb.maxZ, aabb.minX, aabb.minY, aabb.maxZ);

			// Connect corners
			drawLine(g, aabb.minX, aabb.minY, aabb.minZ, aabb.minX, aabb.minY, aabb.maxZ);
			drawLine(g, aabb.minX, aabb.maxY, aabb.minZ, aabb.minX, aabb.maxY, aabb.maxZ);
			drawLine(g, aabb.maxX, aabb.maxY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
			drawLine(g, aabb.maxX, aabb.minY, aabb.minZ, aabb.maxX, aabb.minY, aabb.maxZ);
		}
	}

	private float[] getAabbCanvasCenter(AABB aabb) {
		float[] c = aabb.getCenter();
		return localToCanvas(client, (int) c[0], (int) c[1], (int) c[2]);
	}

	private void drawLocalAabbLabel(Graphics2D g, AABB aabb, String text, boolean backdrop) {
		var p = getAabbCanvasCenter(aabb);
		if (p == null)
			return;

		String[] lines = text.split("\\n");

		Color c = g.getColor();

		FontMetrics fm = g.getFontMetrics();
		int lineHeight = fm.getHeight();
		int totalHeight = lineHeight * lines.length;

		if (backdrop) {
			int totalWidth = 0;
			for (String line : lines)
				totalWidth = max(totalWidth, fm.stringWidth(line));

			g.setColor(BACKDROP_COLOR);
			int pad = 4;
			g.fillRect(
				(int) (p[0] - totalWidth / 2.f - pad),
				(int) (p[1] - totalHeight / 2.f - lineHeight / 2.f - pad),
				totalWidth + pad * 2,
				totalHeight + pad * 2
			);
		}

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int width = fm.stringWidth(line);
			int px = (int) (p[0] - width / 2.f);
			int py = (int) (p[1] - totalHeight / 2.f + lineHeight * i + lineHeight / 2.f);
			g.setColor(Color.BLACK);
			g.drawString(line, px + 1, py + 1);
			g.setColor(c);
			g.drawString(line, px, py);
		}
	}

	private void drawLoadingLines(Graphics2D g) {
		g.setColor(Color.BLUE);
		int min = 16 * LOCAL_TILE_SIZE;
		int max = (SCENE_SIZE - 16) * LOCAL_TILE_SIZE;
		var localAabb = new AABB(min, min, max, max, 0);
		drawLocalAabb(g, localAabb);
	}

	private void drawRegionBoxes(Graphics2D g, SceneContext ctx) {
		if (ctx.sceneBase == null)
			return;
		int regionSize = CHUNK_SIZE * 8;

		for (int x = 0; x < EXTENDED_SCENE_SIZE; x += regionSize) {
			for (int y = 0; y < EXTENDED_SCENE_SIZE; y += regionSize) {
				int regionX = (ctx.sceneBase[0] + x) / regionSize;
				int regionY = (ctx.sceneBase[1] + y) / regionSize;
				int regionId = regionX << 8 | regionY;
				int worldX = regionX * regionSize;
				int worldY = regionY * regionSize;
				var aabb = new AABB(
					worldX,
					worldY,
					worldX + regionSize - 1,
					worldY + regionSize - 1,
					ctx.sceneBase[2] + client.getPlane()
				);
				var localAabb = toLocalAabb(ctx, aabb, .996f);
				g.setColor(TRANSPARENT_YELLOW_50);
				drawLocalAabb(g, localAabb);
				g.setColor(TRANSPARENT_YELLOW_100);
				drawLocalAabbLabel(g, localAabb, "Region ID\n" + regionId, false);
			}
		}
	}

	private void copyToClipboard(String toCopy) {
		copyToClipboard(toCopy, null);
	}

	private void copyToClipboard(String toCopy, @Nullable String description) {
		Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		StringSelection string = new StringSelection(toCopy);
		clipboard.setContents(string, null);
		clientThread.invoke(() -> client.addChatMessage(
			ChatMessageType.GAMEMESSAGE,
			"117 HD",
			"<col=006600>[117 HD] " + (
				description == null ?
					"Copied to clipboard: " + toCopy :
					description
			),
			"117 HD"
		));
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public synchronized MouseEvent mousePressed(MouseEvent e) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return e;

		if (e.isAltDown()) {
			e.consume();

			if (SwingUtilities.isLeftMouseButton(e)) {
				if (!Arrays.equals(selectedAreaAabb, hoveredAreaAabb)) {
					if (TileInfoOverlay.this.hoveredAreaAabb[0] != -1)
						copyToClipboard(visibleAreas[TileInfoOverlay.this.hoveredAreaAabb[0]]
							.aabbs[TileInfoOverlay.this.hoveredAreaAabb[1]]
							.toArgs());

					copyTo(selectedAreaAabb, hoveredAreaAabb);
					return e;
				}

				if (aabbMarkingStage == 0) {
					// Marking first
					System.arraycopy(hoveredWorldPoint, 0, markedWorldPoints[0], 0, 3);
				} else {
					// Done marking
					selections.add(pendingSelection);

					if (selections.size() == 1) {
						copyToClipboard(pendingSelection.toArgs());
					} else {
						StringBuilder sb = new StringBuilder();
						for (int i = 0; i < selections.size(); i++) {
							if (i > 0)
								sb.append(",\n");
							sb.append(selections.get(i).toArgs());
						}
						copyToClipboard(sb.toString(), "Copied " + selections.size() + " AABBs to clipboard");
					}

					pendingSelection = null;
				}
				aabbMarkingStage = (aabbMarkingStage + 1) % 2;
			} else if (SwingUtilities.isRightMouseButton(e)) {
				// Reset selection
				aabbMarkingStage = 0;
				selections.clear();
				pendingSelection = null;
			}
		} else if (SwingUtilities.isRightMouseButton(e)) {
			if (!hoveredGamevals.isEmpty()) {
				if (copiedGamevalsHash != hoveredGamevalsHash) {
					copiedGamevalsHash = hoveredGamevalsHash;
					hoveredGamevalsIndex = 0;
				}
				copyToClipboard('"' + hoveredGamevals.get(hoveredGamevalsIndex) + '"');
				hoveredGamevalsIndex = (hoveredGamevalsIndex + 1) % hoveredGamevals.size();
			}
		}

		return e;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent e) {
		return e;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) {
		if (ctrlHeld) {
			e.consume();
			targetPlane = clamp(targetPlane + e.getWheelRotation(), 0, MAX_Z - 1);
		} else if (altHeld) {
			e.consume();
			selectionIncludeZ = !selectionIncludeZ;
		}

		return e;
	}
}
