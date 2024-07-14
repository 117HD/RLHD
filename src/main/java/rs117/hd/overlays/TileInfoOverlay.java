package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
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
import java.util.function.Function;
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
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ColorUtil;
import org.apache.commons.lang3.tuple.Pair;
import rs117.hd.HdPlugin;
import rs117.hd.data.materials.Material;
import rs117.hd.scene.AreaManager;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.scene.SceneContext;
import rs117.hd.scene.TileOverrideManager;
import rs117.hd.scene.areas.AABB;
import rs117.hd.scene.areas.Area;
import rs117.hd.utils.HDUtils;
import rs117.hd.utils.ModelHash;
import rs117.hd.utils.Vector;

import static net.runelite.api.Constants.*;
import static net.runelite.api.Perspective.*;
import static rs117.hd.scene.SceneContext.SCENE_OFFSET;
import static rs117.hd.scene.tile_overrides.TileOverride.OVERLAY_FLAG;
import static rs117.hd.utils.HDUtils.clamp;

@Slf4j
@Singleton
public class TileInfoOverlay extends Overlay implements MouseListener, MouseWheelListener {
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
	private TileOverrideManager tileOverrideManager;

	@Inject
	private ProceduralGenerator proceduralGenerator;

	@Getter
	private boolean active;

	private int[] mousePos;
	private boolean ctrlHeld;
	private boolean ctrlToggled;
	private boolean shiftHeld;
	private float fontSize = 12;
	private float zoom = 1;

	private int mode;
	private int aabbMarkingStage;
	private AABB pendingSelection;
	private final ArrayList<AABB> selections = new ArrayList<>();
	private final int[] selectedAreaAabb = { -1, 0 };
	private final int[] hoveredAreaAabb = { -1, 0 };
	private final int[][] markedWorldPoints = new int[2][3];
	private int[] hoveredWorldPoint = new int[3];
	private int targetPlane = MAX_Z - 1;

	private SceneContext currentSceneContext;
	private Area[] visibleAreas = new Area[0];

	public TileInfoOverlay() {
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPosition(OverlayPosition.DYNAMIC);
	}

	public void setActive(boolean activate) {
		this.active = activate;
		if (activate) {
			overlayManager.add(this);
			mouseManager.registerMouseListener(this);
			mouseManager.registerMouseWheelListener(this);
		} else {
			overlayManager.remove(this);
			mouseManager.unregisterMouseListener(this);
			mouseManager.unregisterMouseWheelListener(this);
		}
		tileOverrideManager.setTrackReplacements(activate);
	}

	@Override
	public Dimension render(Graphics2D g) {
		// Disable the overlay while loading a scene, since tile overrides aren't thread safe
		if (plugin.isLoadingScene())
			return null;

		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return null;

		if (sceneContext != currentSceneContext) {
			currentSceneContext = sceneContext;

			hoveredAreaAabb[0] = -1;
			hoveredAreaAabb[1] = -1;
			System.arraycopy(hoveredAreaAabb, 0, selectedAreaAabb, 0, 2);

			if (sceneContext.scene.isInstance()) {
				visibleAreas = new Area[0];
			} else {
				AABB sceneBounds = sceneContext.getNonInstancedSceneBounds();
				AABB dummyAabb = new AABB(0, 0);
				visibleAreas = Arrays
					.stream(AreaManager.AREAS)
					.map(area -> {
						var copy = new Area(area.name);
						copy.regions = area.regions;
						copy.regionBoxes = area.regionBoxes;
						copy.rawAabbs = area.rawAabbs;
						copy.normalize();
						copy.aabbs = Arrays.stream(copy.aabbs)
							.map(aabb -> sceneBounds.intersects(aabb) ? aabb : dummyAabb)
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
				mode = (mode + 1) % 3;
		}

		Tile[][][] tiles = sceneContext.scene.getExtendedTiles();
		Point canvasMousePos = client.getMouseCanvasPosition();
		mousePos = null;
		if (canvasMousePos != null && canvasMousePos.getX() != -1 && canvasMousePos.getY() != -1)
			mousePos = new int[] { canvasMousePos.getX(), canvasMousePos.getY() };

		int maxPlane = client.getPlane();
		int minPlane = 0;
		if (ctrlHeld)
			minPlane = maxPlane = targetPlane;

		if (mousePos != null) {
			g.setFont(FontManager.getRunescapeFont());
			g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));

			tileLoop:
			for (int z = maxPlane; z >= minPlane; z--) {
				for (int isBridge = 1; isBridge >= 0; isBridge--) {
					for (int x = 0; x < EXTENDED_SCENE_SIZE; x++) {
						for (int y = 0; y < EXTENDED_SCENE_SIZE; y++) {
							Tile tile = tiles[z][x][y];
							boolean shouldDraw = tile != null && (isBridge == 0 || tile.getBridge() != null);
							if (shouldDraw) {
								if (mode == 0) {
									if (!drawTileInfo(g, sceneContext, tile))
										continue;
								} else {
									g.setColor(Color.CYAN);
									if (isBridge == 1 && tile.getBridge() != null) {
										g.setColor(Color.MAGENTA);
										tile = tile.getBridge();
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

		switch (mode) {
			case 1:
				drawAllIds(g, sceneContext);
				break;
			case 2:
				g.setFont(FontManager.getRunescapeSmallFont());

				if (mousePos != null) {
					hoveredAreaAabb[0] = -1;
					hoveredAreaAabb[1] = 0;
					int[] v = new int[2];
					outer:
					for (int i = 0; i < visibleAreas.length; i++) {
						var area = visibleAreas[i];
						for (int j = 0; j < area.aabbs.length; j++) {
							if (i == selectedAreaAabb[0] && j == selectedAreaAabb[1])
								continue;

							var aabb = toLocalAabb(sceneContext, cropAabb(sceneContext, area.aabbs[j]));
							var p = getAabbCanvasCenter(aabb);
							if (p != null) {
								Vector.subtract(v, mousePos, p);
								if (Vector.dot(v, v) < 26 * 26) {
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
						boolean hovered = areaHovered && j == hoveredAreaAabb[1];
						boolean selected = areaSelected && j == selectedAreaAabb[1];
						if (hovered || selected)
							continue;

						String label = aabb.toArgs();
						if (aabb.isVolume())
							label = area.name + "[" + j + "]\n" + label;

						// Since we have a bunch of AABBs spanning all planes,
						// it would be a bit obnoxious to always render the full AABB
						AABB croppedAabb = cropAabb(sceneContext, aabb);
						var localAabb = toLocalAabb(sceneContext, croppedAabb);

						g.setColor(Color.GRAY);
						drawLocalAabb(g, localAabb);

						g.setColor(Color.LIGHT_GRAY);
						drawLocalAabbLabel(g, localAabb, label);
					}
				}

				if (hoveredAreaAabb[0] != -1) {
					var area = visibleAreas[hoveredAreaAabb[0]];
					var aabb = area.aabbs[hoveredAreaAabb[1]];
					g.setColor(Color.WHITE);

					var localAabb = toLocalAabb(sceneContext, aabb);
					drawLocalAabb(g, localAabb);
					drawLocalAabbLabel(g, localAabb, area.name + "[" + hoveredAreaAabb[1] + "]\n" + aabb.toArgs());
				}

				if (selectedAreaAabb[0] != -1) {
					var area = visibleAreas[selectedAreaAabb[0]];
					var aabb = area.aabbs[selectedAreaAabb[1]];
					g.setColor(Color.CYAN);

					var localAabb = toLocalAabb(sceneContext, aabb);
					drawLocalAabb(g, localAabb);
					drawLocalAabbLabel(g, localAabb, area.name + "[" + selectedAreaAabb[1] + "]\n" + aabb.toArgs());
				}

				break;
		}

		// Update second selection point each frame
		if (aabbMarkingStage == 1) {
			markedWorldPoints[1] = hoveredWorldPoint;
			pendingSelection = new AABB(markedWorldPoints[0], markedWorldPoints[1]);
		}

		for (int i = 0; i < selections.size(); i++) {
			var aabb = selections.get(i);
			var localAabb = toLocalAabb(sceneContext, aabb);
			// Draw selection boxes
			g.setColor(Color.YELLOW);
			drawLocalAabb(g, localAabb);
			g.setFont(FontManager.getRunescapeFont());
			drawLocalAabbLabel(g, localAabb, "Selection[" + i + "]\n" + aabb.toArgs());
		}

		if (pendingSelection != null) {
			var localAabb = toLocalAabb(sceneContext, pendingSelection);
			// Draw current selection box
			g.setColor(Color.YELLOW);
			drawLocalAabb(g, localAabb);
			g.setFont(FontManager.getRunescapeFont());
			drawLocalAabbLabel(g, localAabb, "Selection[" + selections.size() + "]\n" + pendingSelection.toArgs());
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
					if (rect != null) {
						infoDrawn = true;
					}
				}
			}

			poly = getCanvasTilePoly(client, sceneContext.scene, tile);
			if (poly != null && poly.contains(mousePos[0], mousePos[1])) {
				rect = drawTileInfo(g, sceneContext, tile, poly, rect);
				if (rect != null) {
					infoDrawn = true;
				}
			}
		}

		return infoDrawn;
	}

	private Rectangle drawTileInfo(Graphics2D g, SceneContext sceneContext, Tile tile, Polygon poly, Rectangle dodgeRect)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		SceneTileModel model = tile.getSceneTileModel();

		if (!ctrlHeld && (paint == null || (paint.getNeColor() == 12345678 && tile.getBridge() == null)) && model == null)
			return null;

		ArrayList<String> lines = new ArrayList<>();

		if (tile.getBridge() != null)
			lines.add("Bridge");

		Scene scene = sceneContext.scene;
		int tileX = tile.getSceneLocation().getX();
		int tileY = tile.getSceneLocation().getY();
		int tileZ = tile.getRenderLevel();
		int tileExX = tileX + SCENE_OFFSET;
		int tileExY = tileY + SCENE_OFFSET;
		int[] worldPos = sceneContext.sceneToWorld(tileX, tileY, tileZ);

		lines.add("Scene point: " + tileX + ", " + tileY + ", " + tileZ);
		lines.add("World point: " + Arrays.toString(worldPos));
		lines.add(String.format(
			"Region ID: %d (%d, %d)",
			HDUtils.worldToRegionID(worldPos),
			worldPos[0] >> 6,
			worldPos[1] >> 6
		));

		int overlayId = scene.getOverlayIds()[tileZ][tileExX][tileExY];
		var overlay = tileOverrideManager.getOverrideBeforeReplacements(worldPos, OVERLAY_FLAG | overlayId);
		var replacementPath = new StringBuilder(overlay.toString());
		while (true) {
			var replacement = tileOverrideManager.resolveNextReplacement(overlay, tile);
			if (replacement == overlay)
				break;
			replacementPath.append("\n\t⤷ ").append(replacement);
			overlay = replacement;
		}
		lines.add(String.format("Overlay: ID %d -> %s", overlayId, replacementPath));
		lines.add("GroundMaterial: " + overlay.groundMaterial);

		int underlayId = scene.getUnderlayIds()[tileZ][tileExX][tileExY];
		var underlay = tileOverrideManager.getOverrideBeforeReplacements(worldPos, underlayId);
		replacementPath = new StringBuilder(underlay.toString());
		while (true) {
			var replacement = tileOverrideManager.resolveNextReplacement(underlay, tile);
			if (replacement == underlay)
				break;
			replacementPath.append("\n\t⤷ ").append(replacement);
			underlay = replacement;
		}
		lines.add(String.format("Underlay: ID %d -> %s", underlayId, replacementPath));
		lines.add("GroundMaterial: " + underlay.groundMaterial);

		Color polyColor = Color.LIGHT_GRAY;
		if (paint != null)
		{
			polyColor = Color.CYAN;
			lines.add("Tile type: Paint");
			Material material = Material.fromVanillaTexture(paint.getTexture());
			lines.add(String.format("Material: %s (%d)", material.name(), paint.getTexture()));
			int[] hsl = new int[3];
			HDUtils.getSouthWesternMostTileColor(hsl, tile);
			lines.add(String.format("HSL: %s", Arrays.toString(hsl)));

			var override = tileOverrideManager.getOverride(scene, tile, worldPos, OVERLAY_FLAG | overlayId, underlayId);
			lines.add("WaterType: " + proceduralGenerator.seasonalWaterType(override, paint.getTexture()));
		}
		else if (model != null)
		{
			polyColor = Color.ORANGE;
			lines.add("Tile type: Model");
			lines.add(String.format("Face count: %d", model.getFaceX().length));

			HashSet<String> uniqueMaterials = new HashSet<>();
			int numChars = 0;
			if (model.getTriangleTextureId() != null)
			{
				for (int texture : model.getTriangleTextureId())
				{
					String material = String.format("%s (%d)", Material.fromVanillaTexture(texture).name(), texture);
					boolean unique = uniqueMaterials.add(material);
					if (unique)
					{
						numChars += material.length();
					}
				}
			}

			ArrayList<String> materials = new ArrayList<>(uniqueMaterials);
			Collections.sort(materials);

			if (materials.size() <= 1 || numChars < 26)
			{
				StringBuilder sb = new StringBuilder("Materials: { ");
				if (materials.isEmpty())
				{
					sb.append("null");
				}
				else
				{
					String prefix = "";
					for (String m : materials)
					{
						sb.append(prefix).append(m);
						prefix = ", ";
					}
				}
				sb.append(" }");
				lines.add(sb.toString());
			}
			else
			{
				Iterator<String> iter = materials.iterator();
				lines.add("Materials: { " + iter.next() + ",");
				while (iter.hasNext())
				{
					lines.add("\t  " + iter.next() + (iter.hasNext() ? "," : " }"));
				}
			}

			int[] hsl = new int[3];
			HDUtils.getSouthWesternMostTileColor(hsl, tile);
			lines.add(String.format("HSL: %s", Arrays.toString(hsl)));
		}

		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null) {
			lines.add(String.format(
				"Ground Object: ID=%s preori=%d",
				getIdAndImpostorId(groundObject, groundObject.getRenderable()),
				HDUtils.getBakedOrientation(groundObject.getConfig())
			));
		}

		WallObject wallObject = tile.getWallObject();
		if (wallObject != null) {
			lines.add(String.format(
				"Wall Object: ID=%s bakedOri=%d oriA=%d oriB=%d",
				getIdAndImpostorId(wallObject, wallObject.getRenderable1()),
				HDUtils.getBakedOrientation(wallObject.getConfig()),
				wallObject.getOrientationA(),
				wallObject.getOrientationB()
			));
		}

		GameObject[] gameObjects = tile.getGameObjects();
		for (GameObject gameObject : gameObjects) {
			if (gameObject == null)
				continue;
			int height = -1;
			int animationId = -1;
			var renderable = gameObject.getRenderable();
			if (renderable != null) {
				height = renderable.getModelHeight();
				if (renderable instanceof DynamicObject) {
					var anim = ((DynamicObject) renderable).getAnimation();
					if (anim != null)
						animationId = anim.getId();
				}
			}

			lines.add(String.format(
				"%s: ID=%s preori=%d ori=%d height=%d anim=%d",
				ModelHash.getTypeName(ModelHash.getType(gameObject.getHash())),
				getIdAndImpostorId(gameObject, renderable),
				HDUtils.getBakedOrientation(gameObject.getConfig()),
				gameObject.getModelOrientation(),
				height,
				animationId
			));
		}

		for (GraphicsObject graphicsObject : client.getGraphicsObjects()) {
			var lp = graphicsObject.getLocation();
			if (lp.getSceneX() == tileX && lp.getSceneY() == tileY)
				lines.add(String.format("Graphics Object: ID=%s", graphicsObject.getId()));
		}

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

		for (String line : lines)
		{
			Pair<String, String> pair = splitter.apply(line);
			if (pair.getRight().isEmpty())
			{
				int halfWidth = fm.stringWidth(pair.getLeft()) / 2;
				leftWidth = Math.max(leftWidth, halfWidth);
				rightWidth = Math.max(rightWidth, halfWidth);
			}
			else
			{
				leftWidth = Math.max(leftWidth, fm.stringWidth(pair.getLeft()));
				rightWidth = Math.max(rightWidth, fm.stringWidth(pair.getRight()));
			}
		}

		Rectangle2D polyBounds = poly.getBounds2D();
		Point tileCenter = new Point((int) polyBounds.getCenterX(), (int) polyBounds.getCenterY());

		var bounds = g.getClipBounds();

		int totalWidth = leftWidth + rightWidth + space + xPadding * 2;
		Rectangle rect = new Rectangle(
			clamp(tileCenter.getX() - totalWidth / 2, bounds.x, bounds.x + bounds.width - totalWidth),
			clamp(tileCenter.getY() - totalHeight - padding, bounds.y, bounds.y + bounds.height - totalHeight),
			totalWidth,
			totalHeight
		);
		if (dodgeRect != null && dodgeRect.intersects(rect))
		{
			// Avoid overlapping with other tile info
			rect.y = dodgeRect.y - rect.height - padding;
		}

		if (tile.getBridge() != null)
		{
			polyColor = Color.MAGENTA;
		}
		g.setColor(polyColor);
		g.drawPolygon(poly);

		g.setColor(new Color(0, 0, 0, 100));
		g.fillRect(rect.x, rect.y, rect.width, rect.height);

		int offsetY = 0;
		for (String line : lines)
		{
			Pair<String, String> pair = splitter.apply(line);
			offsetY += lineHeight;
			Point p;
			if (pair.getRight().isEmpty())
			{
				// centered
				p = new Point(
					rect.x + rect.width / 2 - fm.stringWidth(pair.getLeft()) / 2,
					rect.y + padding + offsetY);
			}
			else
			{
				// left & right
				p = new Point(
					rect.x + xPadding + leftWidth - fm.stringWidth(pair.getLeft()) + (pair.getRight().startsWith("\t") ? indent : 0),
					rect.y + padding + offsetY);
			}
			OverlayUtil.renderTextLocation(g, p, line, Color.WHITE);
		}

		return rect;
	}

	private String getIdAndImpostorId(TileObject object, @Nullable Renderable renderable) {
		int id = object.getId();
		int impostorId = getIdOrImpostorId(object, renderable);
		return id + (id == impostorId ? "" : " -> " + impostorId);
	}

	private int getIdOrImpostorId(TileObject object, @Nullable Renderable renderable) {
		return ModelHash.getUuidId(ModelHash.generateUuid(client, object.getHash(), renderable));
	}

	public static Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, Tile tile) {
		if (tile == null)
			return null;
		var l = tile.getSceneLocation();
		return getCanvasTilePoly(client, scene, l.getX(), l.getY(), tile.getPlane());
	}

	public static Polygon getCanvasTilePoly(@Nonnull Client client, Scene scene, int... sceneXYplane) {
		int tileExX = sceneXYplane[0] + SCENE_OFFSET;
		int tileExY = sceneXYplane[1] + SCENE_OFFSET;
		if (tileExX < 0 || tileExY < 0 || tileExX >= EXTENDED_SCENE_SIZE || tileExY >= EXTENDED_SCENE_SIZE)
			return null;

		final int swX = sceneXYplane[0] * LOCAL_TILE_SIZE;
		final int swY = sceneXYplane[1] * LOCAL_TILE_SIZE;
		final int neX = (sceneXYplane[0] + 1) * LOCAL_TILE_SIZE;
		final int neY = (sceneXYplane[1] + 1) * LOCAL_TILE_SIZE;

		final int swHeight = getHeight(scene, swX, swY, sceneXYplane[2]);
		final int nwHeight = getHeight(scene, neX, swY, sceneXYplane[2]);
		final int neHeight = getHeight(scene, neX, neY, sceneXYplane[2]);
		final int seHeight = getHeight(scene, swX, neY, sceneXYplane[2]);

		int[] p1 = localToCanvas(client, swX, swY, swHeight);
		int[] p2 = localToCanvas(client, neX, swY, nwHeight);
		int[] p3 = localToCanvas(client, neX, neY, neHeight);
		int[] p4 = localToCanvas(client, swX, neY, seHeight);

		if (p1 == null || p2 == null || p3 == null || p4 == null)
			return null;

		Polygon poly = new Polygon();
		poly.addPoint(p1[0], p1[1]);
		poly.addPoint(p2[0], p2[1]);
		poly.addPoint(p3[0], p3[1]);
		poly.addPoint(p4[0], p4[1]);

		return poly;
	}

	private static int getHeight(Scene scene, int localX, int localY, int plane) {
		int sceneX = (localX >> LOCAL_COORD_BITS) + SCENE_OFFSET;
		int sceneY = (localY >> LOCAL_COORD_BITS) + SCENE_OFFSET;
		if (sceneX < 0 || sceneY < 0 || sceneX >= EXTENDED_SCENE_SIZE || sceneY >= EXTENDED_SCENE_SIZE)
			return 0;

		int[][][] tileHeights = scene.getTileHeights();
		int x = localX & (LOCAL_TILE_SIZE - 1);
		int y = localY & (LOCAL_TILE_SIZE - 1);
		int var8 =
			x * tileHeights[plane][sceneX + 1][sceneY] + (LOCAL_TILE_SIZE - x) * tileHeights[plane][sceneX][sceneY] >> LOCAL_COORD_BITS;
		int var9 = tileHeights[plane][sceneX][sceneY + 1] * (LOCAL_TILE_SIZE - x) + x * tileHeights[plane][sceneX + 1][sceneY + 1]
				   >> LOCAL_COORD_BITS;
		return (LOCAL_TILE_SIZE - y) * var8 + y * var9 >> 7;
	}

	private static int[] localToCanvas(@Nonnull Client client, int x, int y, int z) {
		// Using floats to support coordinates much larger than normal local coordinates
		x -= client.getCameraX();
		y -= client.getCameraY();
		z -= client.getCameraZ();
		int cameraPitch = client.getCameraPitch();
		int cameraYaw = client.getCameraYaw();
		float pitchSin = (float) Math.sin(cameraPitch * UNIT);
		float pitchCos = (float) Math.cos(cameraPitch * UNIT);
		float yawSin = (float) Math.sin(cameraYaw * UNIT);
		float yawCos = (float) Math.cos(cameraYaw * UNIT);
		float x1 = x * yawCos + y * yawSin;
		float y1 = y * yawCos - x * yawSin;
		float y2 = z * pitchCos - y1 * pitchSin;
		float z1 = y1 * pitchCos + z * pitchSin;
		if (z1 >= 1) {
			float scale = client.getScale();
			float pointX = client.getViewportWidth() / 2.f + x1 * scale / z1;
			float pointY = client.getViewportHeight() / 2.f + y2 * scale / z1;
			return new int[] {
				(int) (pointX + client.getViewportXOffset()),
				(int) (pointY + client.getViewportYOffset()),
				(int) Math.min(Integer.MAX_VALUE, z1)
			};
		}

		return null;
	}

	private static String hslString(int color) {
		if (color == 12345678)
			return "HIDDEN";
		return color + " (" + (color >> 10 & 0x3F) + ", " + (color >> 7 & 7) + ", " + (color & 0x7F) + ")";
	}

	private void useZoomAwareFont(Graphics2D g, float scale) {
		zoom = client.get3dZoom() / 1000.f;
		if (zoom > 1.2f) {
			fontSize = Math.min(16, 11 * zoom);
		} else {
			fontSize = Math.max(7.8f, 14 * (float) Math.sqrt(zoom));
		}
		g.setFont(FontManager.getDefaultFont().deriveFont(fontSize * scale));
	}

	private void drawAllIds(Graphics2D g, SceneContext ctx) {
		useZoomAwareFont(g, 1);
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
		var p = Perspective.getCanvasTextLocation(client, g, lp, str, object.getPlane() * 240);
		if (p == null)
			return;
		g.drawString(str, p.getX(), p.getY() + line * fontSize);
	}

	/**
	 * Draw line given local scene coordinates
	 */
	private void drawLine(Graphics2D g, int x1, int y1, int z1, int x2, int y2, int z2) {
		// Using floats to support coordinates much larger than normal local coordinates
		int cameraPitch = client.getCameraPitch();
		int cameraYaw = client.getCameraYaw();
		float pitchSin = (float) Math.sin(cameraPitch * UNIT);
		float pitchCos = (float) Math.cos(cameraPitch * UNIT);
		float yawSin = (float) Math.sin(cameraYaw * UNIT);
		float yawCos = (float) Math.cos(cameraYaw * UNIT);

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

		int scale = client.getScale();
		ax = client.getViewportXOffset() + client.getViewportWidth() / 2.f + ax * scale / az;
		ay = client.getViewportYOffset() + client.getViewportHeight() / 2.f + ay * scale / az;
		bx = client.getViewportXOffset() + client.getViewportWidth() / 2.f + bx * scale / bz;
		by = client.getViewportYOffset() + client.getViewportHeight() / 2.f + by * scale / bz;

		g.drawLine((int) ax, (int) ay, (int) bx, (int) by);
	}

	private AABB toLocalAabb(SceneContext ctx, AABB aabb) {
		int baseX = ctx.scene.getBaseX();
		int baseY = ctx.scene.getBaseY();

		int x1 = (aabb.minX - baseX) * LOCAL_TILE_SIZE;
		int y1 = (aabb.minY - baseY) * LOCAL_TILE_SIZE;
		int x2 = (aabb.maxX + 1 - baseX) * LOCAL_TILE_SIZE;
		int y2 = (aabb.maxY + 1 - baseY) * LOCAL_TILE_SIZE;

		int minZ = 0, maxZ = MAX_Z;
		if (aabb.hasZ()) {
			minZ = HDUtils.clamp(aabb.minZ, 0, MAX_Z);
			maxZ = HDUtils.clamp(aabb.maxZ, 0, MAX_Z) + 1;
		}
		int z1 = Integer.MAX_VALUE;
		int z2 = Integer.MIN_VALUE;

		for (int i = minZ; i < maxZ; i++) {
			int sw = getHeight(ctx.scene, x1, y1, i);
			int nw = getHeight(ctx.scene, x1, y2, i);
			int ne = getHeight(ctx.scene, x2, y2, i);
			int se = getHeight(ctx.scene, x2, y1, i);
			z1 = (int) HDUtils.min(z1, sw, nw, ne, se);
			z2 = (int) HDUtils.max(z2, sw, nw, ne, se);
		}

		return new AABB(x1, y1, z1, x2, y2, z2);
	}

	private AABB cropAabb(SceneContext ctx, AABB aabb) {
		if (aabb.isPoint()) {
			int sceneExX = aabb.minX - ctx.getBaseExX();
			int sceneExY = aabb.minY - ctx.getBaseExY();
			if (sceneExX >= 0 && sceneExY >= 0 && sceneExX < EXTENDED_SCENE_SIZE && sceneExY < EXTENDED_SCENE_SIZE) {
				int minZ = MAX_Z - 1;
				int maxZ = 0;
				int filled = ctx.filledTiles[sceneExX][sceneExY];
				for (int plane = 0; plane < MAX_Z; plane++) {
					if ((filled & (1 << plane)) != 0) {
						minZ = Math.min(minZ, plane);
						maxZ = Math.max(maxZ, plane);
					}
				}
				return new AABB(aabb.minX, aabb.minY, minZ, aabb.minX, aabb.minY, maxZ);
			}
		}

		return new AABB(aabb.minX, aabb.minY, aabb.maxX, aabb.maxY, client.getPlane());
	}

	private void drawLocalAabb(Graphics2D g, AABB aabb) {
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

	private int[] getAabbCanvasCenter(AABB aabb) {
		float[] c = aabb.getCenter();
		return localToCanvas(client, (int) c[0], (int) c[1], (int) c[2]);
	}

	private void drawLocalAabbLabel(Graphics2D g, AABB aabb, String text) {
		var p = getAabbCanvasCenter(aabb);
		if (p == null)
			return;

		String[] lines = text.split("\\n");

		Color c = g.getColor();

		FontMetrics fm = g.getFontMetrics();
		int lineHeight = fm.getHeight();
		int totalHeight = lineHeight * lines.length;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int width = fm.stringWidth(line);
			int px = p[0] - width / 2;
			int py = p[1] - totalHeight / 2 + lineHeight * i + lineHeight / 2;
			g.setColor(Color.BLACK);
			g.drawString(line, px + 1, py + 1);
			g.setColor(c);
			g.drawString(line, px, py);
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
			ColorUtil.wrapWithColorTag("[117 HD] " + (
				description == null ?
					"Copied to clipboard: " + toCopy :
					description
			), Color.GREEN),
			"117 HD"
		));
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent e) {
		return e;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent e) {
		var sceneContext = plugin.getSceneContext();
		if (sceneContext == null)
			return e;

		if (e.isAltDown()) {
			e.consume();

			if (SwingUtilities.isLeftMouseButton(e)) {
				int[] nextSelection = { -1, 0 };
				if (!Arrays.equals(selectedAreaAabb, hoveredAreaAabb)) {
					System.arraycopy(hoveredAreaAabb, 0, nextSelection, 0, 2);
					if (nextSelection[0] != -1)
						copyToClipboard(visibleAreas[nextSelection[0]].aabbs[nextSelection[1]].toArgs());
				}

				if (!Arrays.equals(nextSelection, selectedAreaAabb)) {
					System.arraycopy(nextSelection, 0, selectedAreaAabb, 0, 2);
					return e;
				}

				if (aabbMarkingStage == 0) {
					// Marking first
					markedWorldPoints[0] = hoveredWorldPoint;
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
			targetPlane = HDUtils.clamp(targetPlane + e.getWheelRotation(), 0, MAX_Z - 1);
		}

		return e;
	}
}
