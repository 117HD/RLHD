package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.RuneLite;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.Filepath;
import org.lwjgl.BufferUtils;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.utils.ShaderRecompile;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_ITEM_BACKGROUNDS;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_ITEM_ICONS;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_ITEM_ICON_SURROUNDINGS;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_UI;
import static rs117.hd.overlays.ItemIconRasterizer.ICON_HEIGHT;
import static rs117.hd.overlays.ItemIconRasterizer.ICON_WIDTH;
import static rs117.hd.utils.MathUtils.*;

@Slf4j
@Singleton
public class NativeItemIcons extends WidgetItemOverlay {
	private static final int MAX_ICONS = 1024;
	private static final int MAX_GAME_ICONS = 2048;
	private static final int MAX_ITEMS = 256;
	// Slots are kept for the items of this frame and the last
	private static final int MAX_SLOTS = 2 * MAX_ITEMS;
	private static final int MAX_NEW_ITEMS_PER_FRAME = 16;
	private static final int REMEMBERED_ICONS_PER_FRAME = 32;
	private static final long REMEMBER_INTERVAL_MS = 30_000;
	private static final long SCALE_SETTLE_MS = 1000;
	private static final int[] KEPT_CONTAINERS = { InventoryID.INV, InventoryID.WORN };
	// Native icons can reach a little past the game's icons
	private static final int MARGIN = 2;
	private static final int GRID_WIDTH = ICON_WIDTH + 2 * MARGIN;
	private static final int GRID_HEIGHT = ICON_HEIGHT + 2 * MARGIN;
	private static final int GRID_SIZE = GRID_WIDTH * GRID_HEIGHT;
	private static final int LAYERS_PER_ITEM = 2;
	private static final int[] NO_OVERLAYS = new int[GRID_SIZE];
	private static final float MIN_SHAPE_COVERAGE = .9f;
	private static final int[] VERTEX_ATTRIBUTE_SIZES = { 2, 2, 2, 1, 1, 4, 4, 4 };
	private static final int FLOATS_PER_VERTEX = 20;
	private static final int FLOATS_PER_ITEM = FLOATS_PER_VERTEX * 6;
	private static final int FILL_OFFSET = 12;
	private static final int OUTLINE_OFFSET = 16;
	private static final int[] QUAD_CORNERS = { 0, 1, 2, 2, 1, 3 };
	private static final int[] NEIGHBOR_X = { -1, 1, 0, 0 };
	private static final int[] NEIGHBOR_Y = { 0, 0, -1, 1 };

	private static class Shader extends ShaderProgram {
		private final UniformTexture uniIcons = addUniformTexture("itemIcons");
		private final UniformTexture uniIconSurroundings = addUniformTexture("itemIconSurroundings");
		private final UniformTexture uniBackgrounds = addUniformTexture("itemBackgrounds");
		private final UniformTexture uniUi = addUniformTexture("uiTexture");

		Shader() {
			super(t -> t
				.add(GL_VERTEX_SHADER, "item_icon_vert.glsl")
				.add(GL_FRAGMENT_SHADER, "item_icon_frag.glsl"));
		}

		@Override
		protected void initialize() {
			uniIcons.set(TEXTURE_UNIT_ITEM_ICONS);
			uniIconSurroundings.set(TEXTURE_UNIT_ITEM_ICON_SURROUNDINGS);
			uniBackgrounds.set(TEXTURE_UNIT_ITEM_BACKGROUNDS);
			uniUi.set(TEXTURE_UNIT_UI);
		}
	}

	static class Icon {
		volatile boolean failed;
		volatile boolean uncached;
		float[] surroundings;
		volatile int[] pixels;
		int layer = -1;
		int frame;
	}

	private static class GameIcon {
		final boolean[] item = new boolean[GRID_SIZE];
		final boolean[] shadow = new boolean[GRID_SIZE];
		final boolean[] stackSize = new boolean[GRID_SIZE];
		final boolean[] outline = new boolean[GRID_SIZE];
		final int[] pixels;
		int modelItemId = -1;
		final long key;

		GameIcon(int itemId, int border, int[] item, int[] withStackSize) {
			pixels = item;
			key = hash(hash(0xCBF29CE484222325L, itemId, border), item);
			for (int y = 0; y < ICON_HEIGHT; y++) {
				for (int x = 0; x < ICON_WIDTH; x++) {
					int i = y * ICON_WIDTH + x;
					this.item[grid(x, y)] = item[i] != 0;
					stackSize[grid(x, y)] = withStackSize[i] != item[i];
				}
			}
			for (int y = 0; y < ICON_HEIGHT; y++) {
				for (int x = 0; x < ICON_WIDTH; x++) {
					int g = grid(x, y);
					shadow[g] = !this.item[g] && this.item[grid(x - 1, y - 1)];
					for (int n = 0; n < 4; n++)
						outline[g] |= !this.item[g] && this.item[grid(x + NEIGHBOR_X[n], y + NEIGHBOR_Y[n])];
				}
			}
		}

		static int grid(int x, int y) {
			return (y + MARGIN) * GRID_WIDTH + x + MARGIN;
		}

		// FNV-1a
		private static long hash(long hash, int... values) {
			for (int value : values)
				for (int shift = 0; shift < 32; shift += 8)
					hash = (hash ^ (value >>> shift & 0xFF)) * 0x100000001B3L;
			return hash;
		}
	}

	@RequiredArgsConstructor
	private static class CutItem {
		final int index;
		final Rectangle bounds, drawn;
		final GameIcon gameIcon;
		final Slot slot;
		// Dragged items are only drawn once the rest is, like the game draws them
		int iconLayer;
		float opacity;
		int border;
	}

	// What's behind an item and what other overlays draw over it rarely change, so they're only worked out again when they do
	@RequiredArgsConstructor
	private static class Slot {
		final int layer;
		final int[] behind = new int[GRID_SIZE];
		final int[] cut = new int[GRID_SIZE];
		final int[] background = new int[GRID_SIZE];
		final int[] overlays = new int[GRID_SIZE];
		final int[] elsewhere = new int[GRID_SIZE];
		final boolean[] taken = new boolean[GRID_SIZE];
		final List<RuneMatch> runes = new ArrayList<>();
		List<RuneImage> runeImages;
		boolean kept;
		boolean overlaid;
		boolean takeAll;
		boolean backgroundChanged;
		boolean overlaysChanged = true;
		int shadow;
		int fill;
		int outline;
		int frame;
	}

	@RequiredArgsConstructor
	private static class Layer {
		final ItemIconRasterizer.Mesh mesh;
		final int pitch, yaw, roll;
		final int[] gameIcon;
		final int border;
		final boolean matchColors;
	}

	// RuneLite's Rune Pouch plugin draws small images of the runes in the pouch over it, which are drawn natively too
	@RequiredArgsConstructor
	private static class RuneImage {
		final int itemId;
		final int width, height;
		final int[] pixels;
		// Its opaque pixels, as offsets on an item's grid from where the image is drawn
		final int[] offsets, colors;
		// Where the image is in the shape the rune's model is lined up with
		final int left, top;
		final int[] shape;
		final long key;
	}

	@RequiredArgsConstructor
	private static class RuneMatch {
		final RuneImage image;
		final int x, y;
	}

	private class OverlayCapture extends WidgetItemOverlay {
		OverlayCapture() {
			showOnInventory();
			showOnBank();
			showOnEquipment();
			setPriority(PRIORITY_HIGHEST + 1);
		}

		void showOnInterface(int groupId) {
			drawAfterInterface(groupId);
		}

		void showAfterLayer(int layerId) {
			drawAfterLayer(layerId);
		}

		@Override
		public Dimension render(Graphics2D graphics) {
			var buffer = client.getBufferProvider();
			for (var item : uncaptured)
				captureOverlays(buffer.getPixels(), buffer.getWidth(), item);
			uncaptured.clear();
			return null;
		}

		@Override
		public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {}
	}

	// The game draws the dragged item after the rest of the interface
	private class DraggedItems extends Overlay {
		DraggedItems() {
			setPosition(OverlayPosition.DYNAMIC);
			setLayer(OverlayLayer.ABOVE_WIDGETS);
			setPriority(PRIORITY_LOW - 1);
		}

		@Override
		public Dimension render(Graphics2D graphics) {
			var buffer = client.getBufferProvider();
			for (var item : draggedCuts)
				drawDraggedItem(buffer.getPixels(), buffer.getWidth(), item);
			draggedCuts.clear();
			return null;
		}
	}

	private static final Icon PENDING = new Icon();
	private static final GameIcon UNKNOWN = new GameIcon(-1, 0, new int[ICON_WIDTH * ICON_HEIGHT], new int[ICON_WIDTH * ICON_HEIGHT]);
	// Drawn over an item, with nothing behind it
	private static final Slot NO_SLOT = new Slot(MAX_SLOTS * LAYERS_PER_ITEM);

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	private final Shader shader = new Shader();
	private final OverlayCapture overlayCapture = new OverlayCapture();
	private final DraggedItems draggedItemsOverlay = new DraggedItems();

	private ExecutorService executor;
	private boolean active;
	private boolean prepared;
	private int vao;
	private int vbo;
	private int texIcons;
	private int texIconSurroundings;
	private int texBackgrounds;

	private float scaleX;
	private float scaleY;
	private float newScaleX;
	private float newScaleY;
	private long newScaleSince;
	private double brightness;
	private Filepath cacheFolder;
	private ItemIconCache cache;
	@Nullable
	private CompletableFuture<long[]> remembered;
	private int rememberedLoaded;
	private long account = -1;
	private boolean iconsChanged;
	private long lastRemembered;

	private final Map<Long, Icon> icons = new HashMap<>();
	private final ArrayDeque<Integer> freeLayers = new ArrayDeque<>();
	private final List<Icon> uploads = new ArrayList<>();
	private final Map<Long, GameIcon> gameIcons = new LinkedHashMap<>(MAX_GAME_ICONS, .75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, GameIcon> eldest) {
			return size() > MAX_GAME_ICONS;
		}
	};
	private final Map<Long, Integer> stackModels = new HashMap<>();
	private final Map<Long, Integer> stackModelSearches = new HashMap<>();
	private final Map<Integer, Item[]> containers = new HashMap<>();

	// Where other item overlays draw, so they can be fitted to the native icons
	private final Set<Integer> standardHooks;
	// Layers of other interfaces, whose items are cut out right after them
	private final Set<Integer> itemLayers = new HashSet<>();
	private final Set<Integer> newItemLayers = new HashSet<>();
	private final Map<Widget, Rectangle> visibleAreas = new IdentityHashMap<>();

	private int frame;
	private int newItemsThisFrame;
	private int itemCount;
	private final Set<Rectangle> cutThisFrame = new HashSet<>();
	private final Map<Long, Slot> slots = new HashMap<>();
	private final List<CutItem> uncaptured = new ArrayList<>();
	private final List<WidgetItem> draggedItems = new ArrayList<>();
	private final List<CutItem> draggedCuts = new ArrayList<>();
	private final Slot[] itemSlots = new Slot[MAX_ITEMS];
	private final ArrayDeque<Integer> freeSlotLayers = new ArrayDeque<>();
	private final FloatBuffer vertices = BufferUtils.createFloatBuffer(MAX_ITEMS * FLOATS_PER_ITEM);
	private final boolean[] known = new boolean[GRID_SIZE];
	private final boolean[] spread = new boolean[GRID_SIZE];
	private final int[] shadeColors = new int[GRID_SIZE];
	private final int[] overlays = new int[GRID_SIZE];
	private final boolean[] captured = new boolean[GRID_SIZE];
	private final boolean[] runePixels = new boolean[GRID_SIZE];

	private int[] palette;
	private double paletteBrightness;
	private volatile List<RuneImage> runeImages;

	public NativeItemIcons() {
		showOnInventory();
		showOnBank();
		showOnEquipment();
		standardHooks = Set.copyOf(getDrawHooks());
		// Before other item overlays
		setPriority(PRIORITY_LOW - 1);
	}

	public void startUp(Filepath pluginDirectory) {
		try {
			shader.compile(plugin.getShaderIncludes());
		} catch (ShaderException | IOException ex) {
			log.error("Failed to compile the item icon shader, so item icons will not be drawn natively:", ex);
			return;
		}

		vao = glGenVertexArrays();
		vbo = glGenBuffers();
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vbo);
		glBufferData(GL_ARRAY_BUFFER, (long) vertices.capacity() * Float.BYTES, GL_STREAM_DRAW);
		int offset = 0;
		for (int i = 0; i < VERTEX_ATTRIBUTE_SIZES.length; i++) {
			glVertexAttribPointer(i, VERTEX_ATTRIBUTE_SIZES[i], GL_FLOAT, false, FLOATS_PER_VERTEX * Float.BYTES, offset * Float.BYTES);
			glEnableVertexAttribArray(i);
			offset += VERTEX_ATTRIBUTE_SIZES[i];
		}

		texBackgrounds = glGenTextures();
		glActiveTexture(TEXTURE_UNIT_ITEM_BACKGROUNDS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texBackgrounds);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, GRID_WIDTH, GRID_HEIGHT, (MAX_SLOTS + 1) * LAYERS_PER_ITEM, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
		setTextureParameters(GL_NEAREST, GL_CLAMP_TO_EDGE);
		uploadSlotLayer(NO_SLOT.layer, NO_OVERLAYS);
		uploadSlotLayer(NO_SLOT.layer + 1, NO_OVERLAYS);
		freeSlotLayers.clear();
		for (int i = 0; i < MAX_SLOTS; i++)
			freeSlotLayers.push(i * LAYERS_PER_ITEM);

		texIcons = glGenTextures();
		texIconSurroundings = glGenTextures();
		executor = Executors.newFixedThreadPool(2, r -> {
			var thread = new Thread(r, "117 HD - Item icons");
			thread.setDaemon(true);
			return thread;
		});
		cacheFolder = pluginDirectory.joinSegment("item-icons");
		executor.execute(() -> ItemIconCache.removeUnused(cacheFolder));

		showOnInterface(client.getTopLevelInterfaceId());
		for (var node : client.getComponentTable())
			showOnInterface(node.getId());
		// Also when turned on after logging in, before any of them changes
		for (int id : new int[] { InventoryID.INV, InventoryID.WORN, InventoryID.BANK }) {
			var container = client.getItemContainer(id);
			if (container != null)
				containers.put(id, container.getItems());
		}
		overlayManager.add(this);
		overlayManager.add(overlayCapture);
		overlayManager.add(draggedItemsOverlay);
		eventBus.register(this);
		active = true;
	}

	public void shutDown() {
		if (!active)
			return;
		active = false;
		eventBus.unregister(this);
		overlayManager.remove(this);
		overlayManager.remove(overlayCapture);
		overlayManager.remove(draggedItemsOverlay);
		executor.shutdownNow();
		executor = null;

		glDeleteTextures(texIcons);
		glDeleteTextures(texIconSurroundings);
		glDeleteTextures(texBackgrounds);
		glDeleteBuffers(vbo);
		glDeleteVertexArrays(vao);
		texIcons = texIconSurroundings = texBackgrounds = vbo = vao = 0;
		shader.destroy();

		icons.clear();
		gameIcons.clear();
		stackModels.clear();
		stackModelSearches.clear();
		slots.clear();
		containers.clear();
		newItemLayers.clear();
		visibleAreas.clear();
		remembered = null;
		account = -1;
		scaleX = scaleY = newScaleX = newScaleY = 0;
	}

	@Subscribe
	public void onShaderRecompile(ShaderRecompile event) throws ShaderException, IOException {
		shader.compile(event.includes);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		if (!newItemLayers.isEmpty()) {
			for (int layerId : newItemLayers) {
				drawAfterLayer(layerId);
				overlayCapture.showAfterLayer(layerId);
			}
			itemLayers.addAll(newItemLayers);
			newItemLayers.clear();
			updateHooks();
		}

		frame++;
		for (var it = slots.values().iterator(); it.hasNext(); ) {
			var slot = it.next();
			if (slot.frame < frame - 1) {
				freeSlotLayers.push(slot.layer);
				it.remove();
			}
		}
		newItemsThisFrame = 0;
		itemCount = 0;
		vertices.clear();
		cutThisFrame.clear();
		visibleAreas.clear();
		uncaptured.clear();
		draggedItems.clear();
		draggedCuts.clear();
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event) {
		if (!iconsChanged)
			return;
		// Not on the client thread, which may still be drawing
		try {
			var written = remember();
			if (written != null)
				event.waitFor(written);
		} catch (ConcurrentModificationException ignored) {
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (showOnInterface(event.getGroupId()))
			updateHooks();
	}

	// Re-added so the overlay manager picks up the new hooks
	private void updateHooks() {
		overlayManager.remove(this);
		overlayManager.remove(overlayCapture);
		overlayManager.add(this);
		overlayManager.add(overlayCapture);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		containers.put(event.getContainerId(), event.getItemContainer().getItems());
		prepared = false;
	}

	private boolean showOnInterface(int groupId) {
		if (groupId == -1 || getDrawHooks().contains(groupId << 16 | 0xFFFF) || isInsideStandardHook(groupId))
			return false;
		drawAfterInterface(groupId);
		overlayCapture.showOnInterface(groupId);
		return true;
	}

	// Interfaces inside one with a standard hook, like the deposit box's slot locks, would be handed its items after drawing over them
	private boolean isInsideStandardHook(int groupId) {
		for (var node : client.getComponentTable())
			if (node.getId() == groupId)
				return standardHooks.contains(WidgetUtil.componentToInterface((int) node.getHash()) << 16 | 0xFFFF);
		return false;
	}

	@Override
	public Dimension render(Graphics2D graphics) {
		super.render(graphics);
		for (var widgetItem : draggedItems)
			cutOut(widgetItem);
		draggedItems.clear();
		return null;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem) {
		if (!isHooked(widgetItem.getWidget()))
			return;

		if (isDragged(widgetItem)) {
			draggedItems.add(widgetItem);
		} else {
			cutOut(widgetItem);
		}
	}

	// Items are cut out right after their own layer, before the interface can draw over them, except where other item overlays draw
	private boolean isHooked(Widget widget) {
		int layerId = widget.getParentId();
		if (layerId == -1 || itemLayers.contains(layerId) || standardHooks.contains(layerId) ||
			standardHooks.contains(WidgetUtil.componentToInterface(widget.getId()) << 16 | 0xFFFF))
			return true;
		newItemLayers.add(layerId);
		return false;
	}

	private boolean isDragged(WidgetItem widgetItem) {
		return widgetItem.getWidget() == client.getDraggedWidget();
	}

	// Layers only show their children within their own bounds, and their parents'
	private Rectangle visibleArea(Widget widget) {
		var parent = widget.getParent();
		var area = visibleAreas.get(parent);
		if (area == null) {
			area = parent.getBounds();
			for (var ancestor = parent.getParent(); ancestor != null; ancestor = ancestor.getParent())
				area = area.intersection(ancestor.getBounds());
			visibleAreas.put(parent, area);
		}
		return area;
	}

	private void cutOut(WidgetItem widgetItem) {
		var icon = findIcon(widgetItem);
		if (icon == null || icon.layer == -1 || itemCount == MAX_ITEMS)
			return;

		var widget = widgetItem.getWidget();
		boolean dragged = isDragged(widgetItem);
		var bounds = widgetItem.getDraggingCanvasBounds() != null ? widgetItem.getDraggingCanvasBounds() : widgetItem.getCanvasBounds();
		var visible = bounds.intersection(dragged ? widget.getParent().getBounds() : visibleArea(widget));
		// The game only reports items inside its clip, so the parents are still where they were before they were shown
		if (visible.isEmpty() && !dragged)
			visible = bounds;
		// Some interfaces report their items twice
		if (visible.isEmpty() || !cutThisFrame.add(bounds))
			return;
		icon.frame = frame;

		var buffer = client.getBufferProvider();
		// Partly visible items stay clipped like the interface clips them
		var drawn = !visible.equals(bounds) ? visible :
			new Rectangle(bounds.x - MARGIN, bounds.y - MARGIN, GRID_WIDTH, GRID_HEIGHT)
				.intersection(new Rectangle(buffer.getWidth(), buffer.getHeight()));

		// The game draws dragged items half transparent
		float opacity = (dragged ? 128 : 256 - widget.getOpacity()) / 256f;
		var gameIcon = getGameIcon(widgetItem.getId(), widgetItem.getQuantity(), widget.getItemQuantityMode(), widget.getBorderType(), true);
		long slotKey = GameIcon.hash(gameIcon.key, bounds.x, bounds.y, drawn.x, drawn.y, drawn.width, drawn.height);
		var slot = slots.get(slotKey);
		if (slot == null) {
			slot = new Slot(freeSlotLayers.pop());
			slots.put(slotKey, slot);
		}
		slot.frame = frame;
		cutOut(buffer.getPixels(), buffer.getWidth(), bounds, drawn, gameIcon, slot);
		var cutItem = new CutItem(dragged ? -1 : itemCount, bounds, drawn, gameIcon, slot);
		uncaptured.add(cutItem);
		if (dragged) {
			cutItem.iconLayer = icon.layer;
			cutItem.opacity = opacity;
			cutItem.border = widget.getBorderType();
			draggedCuts.add(cutItem);
			return;
		}
		itemSlots[itemCount] = slot;
		putQuad(bounds, drawn, icon.layer, slot.layer, opacity, widget.getBorderType(), slot.shadow);
	}

	private void putQuad(Rectangle bounds, Rectangle drawn, int iconLayer, int slotLayer, float opacity, int border, int shadow) {
		int canvasWidth = client.getCanvasWidth();
		int canvasHeight = client.getCanvasHeight();
		var quad = new Rectangle(drawn.x - 1, drawn.y - 1, drawn.width + 2, drawn.height + 2);
		for (int corner : QUAD_CORNERS) {
			int x = corner % 2 == 0 ? quad.x : quad.x + quad.width;
			int y = corner < 2 ? quad.y : quad.y + quad.height;
			vertices
				.put(x * 2f / canvasWidth - 1)
				.put(1 - y * 2f / canvasHeight)
				.put((float) (x - bounds.x) / ICON_WIDTH)
				.put((float) (y - bounds.y) / ICON_HEIGHT)
				.put(iconLayer)
				.put(slotLayer)
				.put(opacity)
				.put(border);
			putColor(shadow);
			putColor(0);
			putColor(0);
		}
		itemCount++;
	}

	private void putColor(int argb) {
		vertices
			.put((argb >> 16 & 0xFF) / 255f)
			.put((argb >> 8 & 0xFF) / 255f)
			.put((argb & 0xFF) / 255f)
			.put((argb >>> 24) / 255f);
	}

	private void setColor(int item, int offset, int argb) {
		int end = vertices.position();
		for (int corner = 0; corner < QUAD_CORNERS.length; corner++) {
			vertices.position(item * FLOATS_PER_ITEM + corner * FLOATS_PER_VERTEX + offset);
			putColor(argb);
		}
		vertices.position(end);
	}

	@Nullable
	private Icon findIcon(WidgetItem widgetItem) {
		var bounds = widgetItem.getCanvasBounds();
		if (scaleX * scaleY <= 1 || bounds.width != ICON_WIDTH || bounds.height != ICON_HEIGHT)
			return null;

		var widget = widgetItem.getWidget();
		// Selected items use the normal icon, and the shader adds their white border
		return findIcon(widgetItem.getId(), widgetItem.getQuantity(), widget.getItemQuantityMode(), min(widget.getBorderType(), 1), true);
	}

	@Nullable
	private Icon findIcon(int itemId, int quantity, int quantityMode, int border, boolean visible) {
		var gameIcon = getGameIcon(itemId, quantity, quantityMode, border, visible);
		if (gameIcon == UNKNOWN)
			return PENDING;
		if (gameIcon == null)
			return null;

		var icon = icons.get(gameIcon.key);
		if (icon == null) {
			if (!lookInto(visible))
				return PENDING;

			icon = loadIcon(gameIcon.key);
			icons.put(gameIcon.key, icon);
			iconsChanged = true;
		} else if (icon.uncached) {
			int modelItemId = findModelItem(itemId, quantity, border, gameIcon);
			if (modelItemId == -1)
				return PENDING;
			icon.uncached = false;
			if (modelItemId == -2) {
				icon.failed = true;
				var cache = this.cache;
				executor.execute(() -> cache.save(gameIcon.key, null, null));
			} else {
				try {
					drawIcon(icon, modelItemId, border, gameIcon.key);
				} catch (RuntimeException ex) {
					log.warn("Unable to draw the icon of item {}:", modelItemId, ex);
					icon.failed = true;
				}
			}
		}
		return icon.failed ? null : icon;
	}

	private boolean lookInto(boolean visible) {
		if (!visible && newItemsThisFrame >= MAX_NEW_ITEMS_PER_FRAME)
			return false;
		newItemsThisFrame++;
		return true;
	}

	private void prepareIcons() {
		// What's carried and worn stays ready, even while it isn't shown
		for (int id : KEPT_CONTAINERS) {
			var items = containers.get(id);
			if (items == null)
				continue;
			for (var item : items) {
				if (item.getId() == -1)
					continue;
				var icon = findIcon(item.getId(), item.getQuantity(), ItemQuantityMode.NEVER, 1, false);
				if (icon != null && icon != PENDING)
					icon.frame = frame;
			}
		}

		if (prepared)
			return;
		boolean ready = true;
		for (var items : containers.values()) {
			for (var item : items) {
				if (icons.size() >= MAX_ICONS - MAX_ITEMS)
					return;
				if (item.getId() == -1)
					continue;
				var icon = findIcon(item.getId(), item.getQuantity(), ItemQuantityMode.NEVER, 1, false);
				// A stack whose model is still being looked for doesn't hold up the rest
				if (icon == PENDING && newItemsThisFrame >= MAX_NEW_ITEMS_PER_FRAME)
					return;
				ready &= icon == null || icon != PENDING && icon.layer != -1;
			}
		}
		prepared = ready;
	}

	@Nullable
	private GameIcon getGameIcon(int itemId, int quantity, int quantityMode, int border, boolean visible) {
		long key = (long) quantity << 24 | (long) itemId << 4 | quantityMode << 2 | border;
		var cached = gameIcons.get(key);
		if (cached != null || gameIcons.containsKey(key))
			return cached;
		if (!lookInto(visible))
			return UNKNOWN;

		GameIcon gameIcon = null;
		int[] item = gamePixels(itemId, quantity, border, ItemQuantityMode.NEVER, false);
		int[] withStackSize = item == null || quantityMode == ItemQuantityMode.NEVER ? item :
			gamePixels(itemId, quantity, border, quantityMode, false);
		if (withStackSize != null)
			gameIcon = new GameIcon(itemId, border, item, withStackSize);
		gameIcons.put(key, gameIcon);
		return gameIcon;
	}

	// Stacks like coins and arrows show the model of an unnamed item, which the API does not expose
	private int findModelItem(int itemId, int quantity, int border, GameIcon gameIcon) {
		if (gameIcon.modelItemId == -1) {
			var definition = client.getItemDefinition(itemId);
			if (definition.getPlaceholderTemplateId() != -1)
				gameIcon.modelItemId = definition.getPlaceholderId();
			else if (quantity == 1 || Arrays.equals(gameIcon.pixels, gamePixels(itemId, 1, border, ItemQuantityMode.NEVER, false)))
				gameIcon.modelItemId = itemId;
			else
				return findStackModel(itemId, border, gameIcon.pixels);
		}
		return gameIcon.modelItemId;
	}

	private int findStackModel(int itemId, int border, int[] stackIcon) {
		long key = (long) Arrays.hashCode(stackIcon) << 32 | (long) itemId << 2 | border;
		var found = stackModels.get(key);
		if (found != null)
			return found == -1 ? -2 : found;

		int itemCount = client.getItemCount();
		int distance = stackModelSearches.getOrDefault(key, 0);
		for (int checked = 0; checked < 256 && newItemsThisFrame < MAX_NEW_ITEMS_PER_FRAME; checked++) {
			distance++;
			if (itemId + distance / 2 >= itemCount && itemId - distance / 2 < 0) {
				stackModels.put(key, -1);
				stackModelSearches.remove(key);
				return -2;
			}
			int candidate = itemId + (distance % 2 == 1 ? distance / 2 + 1 : -distance / 2);
			if (candidate < 0 || candidate >= itemCount || !"null".equals(client.getItemDefinition(candidate).getName()))
				continue;
			newItemsThisFrame++;
			if (Arrays.equals(stackIcon, gamePixels(candidate, 1, border, ItemQuantityMode.NEVER, false))) {
				stackModels.put(key, candidate);
				stackModelSearches.remove(key);
				return candidate;
			}
		}
		stackModelSearches.put(key, distance);
		return -1;
	}

	@Nullable
	private int[] gamePixels(int itemId, int quantity, int border, int quantityMode, boolean noted) {
		var sprite = client.createItemSprite(itemId, quantity, border, 0, quantityMode, noted, Constants.CLIENT_DEFAULT_ZOOM);
		return sprite == null ? null : sprite.getPixels();
	}

	private Icon loadIcon(long key) {
		var icon = new Icon();
		var cache = this.cache;
		executor.execute(() -> icon.uncached = !cache.load(key, icon));
		return icon;
	}

	private void drawIcon(Icon icon, int itemId, int border, long key) {
		var item = client.getItemDefinition(itemId);
		// Notes are the note's paper with the item drawn smaller on top
		var layers = item.getNote() == -1 ?
			new Layer[] { loadLayer(itemId, 1, false, border) } :
			new Layer[] { loadLayer(item.getNote(), 1, false, border), loadLayer(item.getLinkedNoteId(), 10, true, 1) };
		for (var layer : layers) {
			if (layer == null) {
				icon.failed = true;
				return;
			}
		}
		drawIcon(icon, layers, itemId, key);
	}

	private void drawIcon(Icon icon, Layer[] layers, int itemId, long key) {
		float scaleX = this.scaleX;
		float scaleY = this.scaleY;
		int width = iconWidth();
		int height = iconHeight();
		double brightness = this.brightness;
		var cache = this.cache;
		executor.execute(() -> {
			try {
				int[] palette = getPalette(brightness);
				int[] pixels = null;
				for (var layer : layers) {
					var rasterizer = new ItemIconRasterizer(layer.mesh, layer.pitch, layer.yaw, layer.roll);
					if (!rasterizer.lineUpWith(layer.gameIcon, palette, layer.matchColors)) {
						icon.failed = true;
						cache.save(key, null, null);
						return;
					}
					int[] drawn = rasterizer.draw(scaleX, scaleY, MARGIN, layer.border, palette);
					pixels = pixels == null ? drawn : ItemIconRasterizer.drawOver(drawn, pixels);
				}
				var surroundings = ItemIconRasterizer.surroundings(pixels, width, height, scaleX, scaleY);
				icon.surroundings = surroundings;
				icon.pixels = pixels;
				cache.save(key, pixels, surroundings);
			} catch (Throwable ex) {
				log.warn("Unable to draw the icon of item {}:", itemId, ex);
				icon.failed = true;
			}
		});
	}

	@Nullable
	private Layer loadLayer(int itemId, int quantity, boolean noted, int border) {
		var item = client.getItemDefinition(itemId);
		var mesh = loadMesh(item);
		int[] gameIcon = gamePixels(itemId, quantity, 0, ItemQuantityMode.NEVER, noted);
		if (mesh == null || gameIcon == null)
			return null;
		return new Layer(mesh, item.getXan2d(), item.getYan2d(), item.getZan2d(), gameIcon, border, true);
	}

	@Nullable
	private ItemIconRasterizer.Mesh loadMesh(ItemComposition item) {
		var data = client.loadModelData(item.getInventoryModel());
		if (data == null)
			return null;

		short[] find = item.getColorToReplace();
		short[] replace = item.getColorToReplaceWith();
		if (find != null) {
			data = data.cloneColors();
			for (int i = 0; i < find.length; i++)
				data.recolor(find[i], replace[i]);
		}

		find = item.getTextureToReplace();
		replace = item.getTextureToReplaceWith();
		if (find != null) {
			try {
				var retextured = data.cloneTextures();
				for (int i = 0; i < find.length; i++)
					retextured.retexture(find[i], replace[i]);
				data = retextured;
			} catch (NullPointerException ex) {
				// The client can't clone a model without textures, which has nothing to retexture anyway
			}
		}

		// Lit like the game lights item models
		var model = data.light(item.getAmbient() + 64, item.getContrast() + 768, -50, -10, -50);
		return ItemIconRasterizer.Mesh.copyOf(model, client.getTextureProvider());
	}

	private synchronized int[] getPalette(double brightness) {
		if (palette == null || paletteBrightness != brightness) {
			palette = ItemIconRasterizer.palette(brightness);
			paletteBrightness = brightness;
		}
		return palette;
	}

	public boolean hasItems() {
		return active && itemCount > 0;
	}

	public void render(int[] uiResolution, int[] actualUiResolution) {
		if (!active)
			return;

		glActiveTexture(TEXTURE_UNIT_ITEM_ICON_SURROUNDINGS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texIconSurroundings);
		glActiveTexture(TEXTURE_UNIT_ITEM_ICONS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texIcons);
		for (var icon : icons.values())
			if (icon.pixels != null)
				uploads.add(icon);
		for (var icon : uploads) {
			if (icon.layer == -1 && (icon.layer = takeLayer()) == -1)
				continue;
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, icon.layer, iconWidth(), iconHeight(), 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, icon.pixels);
			glActiveTexture(TEXTURE_UNIT_ITEM_ICON_SURROUNDINGS);
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, icon.layer, iconWidth(), iconHeight(), 1, GL_RED, GL_FLOAT, icon.surroundings);
			glActiveTexture(TEXTURE_UNIT_ITEM_ICONS);
			icon.pixels = null;
			icon.surroundings = null;
		}
		uploads.clear();

		if (itemCount > 0) {
			glActiveTexture(TEXTURE_UNIT_ITEM_BACKGROUNDS);
			glBindTexture(GL_TEXTURE_2D_ARRAY, texBackgrounds);
			// Only what changed is uploaded, right before it's drawn
			for (int i = 0; i < itemCount; i++) {
				var slot = itemSlots[i];
				if (slot.backgroundChanged)
					uploadSlotLayer(slot.layer, slot.background);
				if (slot.overlaysChanged)
					uploadSlotLayer(slot.layer + 1, slot.overlaid ? slot.elsewhere : NO_OVERLAYS);
				slot.backgroundChanged = slot.overlaysChanged = false;
			}

			shader.use();
			glBindVertexArray(vao);
			glBindBuffer(GL_ARRAY_BUFFER, vbo);
			glBufferSubData(GL_ARRAY_BUFFER, 0, vertices.flip());
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
			glDrawArrays(GL_TRIANGLES, 0, itemCount * 6);
		}

		// Rounded, so small window resizes don't invalidate the icons kept on disk
		float roundedScaleX = round(8f * actualUiResolution[0] / uiResolution[0]) / 8f;
		float roundedScaleY = round(8f * actualUiResolution[1] / uiResolution[1]) / 8f;
		if (roundedScaleX != newScaleX || roundedScaleY != newScaleY) {
			newScaleX = roundedScaleX;
			newScaleY = roundedScaleY;
			newScaleSince = System.currentTimeMillis();
		}
		// Only once a new scale is kept for a moment, so passing sizes don't each fill a cache,
		// and not on the login screen, which is scaled differently and has no items
		boolean scaleSettled = cache == null || System.currentTimeMillis() - newScaleSince >= SCALE_SETTLE_MS;
		boolean loggedIn = client.getGameState().getState() >= GameState.LOADING.getState();
		// The same brightness reads as 0.8 before logging in and as a float after
		double newBrightness = (float) client.getTextureProvider().getBrightness();
		if (loggedIn && (scaleSettled && (newScaleX != scaleX || newScaleY != scaleY) || newBrightness != brightness)) {
			// The game's icons depend on the brightness too
			if (newBrightness != brightness)
				gameIcons.clear();
			scaleX = newScaleX;
			scaleY = newScaleY;
			brightness = newBrightness;
			remember();
			cache = new ItemIconCache(cacheFolder, scaleX, scaleY, brightness, iconWidth(), iconHeight());
			executor.execute(cache::markUsed);
			account = client.getAccountHash();
			startRemembered();
			clearIcons();
			glActiveTexture(TEXTURE_UNIT_ITEM_ICONS);
			glBindTexture(GL_TEXTURE_2D_ARRAY, texIcons);
			glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, iconWidth(), iconHeight(), MAX_ICONS, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			setTextureParameters(GL_LINEAR, GL_CLAMP_TO_BORDER);
			glActiveTexture(TEXTURE_UNIT_ITEM_ICON_SURROUNDINGS);
			glBindTexture(GL_TEXTURE_2D_ARRAY, texIconSurroundings);
			glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_R8, iconWidth(), iconHeight(), MAX_ICONS, 0, GL_RED, GL_UNSIGNED_BYTE, 0);
			setTextureParameters(GL_LINEAR, GL_CLAMP_TO_BORDER);
			return;
		}

		// Not on the login screen, so a logout keeps the icons until another account logs in
		long newAccount = client.getAccountHash();
		if (newAccount != -1 && newAccount != account && cache != null) {
			remember();
			account = newAccount;
			startRemembered();
			clearIcons();
		}

		if (scaleX * scaleY > 1) {
			loadRemembered();
			prepareIcons();
		}
		if (iconsChanged && System.currentTimeMillis() - lastRemembered > REMEMBER_INTERVAL_MS)
			remember();
	}

	private void startRemembered() {
		long account = this.account;
		var cache = this.cache;
		remembered = account == -1 ? null : CompletableFuture.supplyAsync(() -> cache.loadRemembered(account), executor);
		rememberedLoaded = 0;
	}

	// Icons shown last time are loaded from the start, so they don't pop in the first time they're shown
	private void loadRemembered() {
		if (remembered == null || !remembered.isDone())
			return;
		long[] keys = remembered.join();
		for (int i = 0; i < REMEMBERED_ICONS_PER_FRAME && rememberedLoaded < keys.length; i++) {
			if (icons.size() >= MAX_ICONS - MAX_ITEMS)
				rememberedLoaded = keys.length;
			else
				icons.computeIfAbsent(keys[rememberedLoaded++], this::loadIcon);
		}
		if (rememberedLoaded == keys.length)
			remembered = null;
	}

	@Nullable
	private CompletableFuture<Void> remember() {
		lastRemembered = System.currentTimeMillis();
		// Not while still loading what was remembered, which would be forgotten
		if (cache == null || account == -1 || remembered != null)
			return null;
		iconsChanged = false;
		long[] keys = icons.entrySet().stream()
			.filter(entry -> !entry.getValue().uncached)
			.sorted((a, b) -> Integer.compare(b.getValue().frame, a.getValue().frame))
			.mapToLong(Map.Entry::getKey)
			.toArray();
		var cache = this.cache;
		long account = this.account;
		return CompletableFuture.runAsync(() -> cache.remember(account, keys), executor);
	}

	private void clearIcons() {
		prepared = false;
		icons.clear();
		freeLayers.clear();
		for (int i = 0; i < MAX_ICONS; i++)
			freeLayers.push(i);
	}

	// Once there's no room left, the icon drawn longest ago makes way, and is loaded again when it's needed
	private int takeLayer() {
		if (!freeLayers.isEmpty())
			return freeLayers.pop();

		Map.Entry<Long, Icon> oldest = null;
		for (var entry : icons.entrySet()) {
			var icon = entry.getValue();
			if (icon.layer != -1 && icon.frame < frame && (oldest == null || icon.frame < oldest.getValue().frame))
				oldest = entry;
		}
		if (oldest == null)
			return -1;
		var icon = icons.remove(oldest.getKey());
		int layer = icon.layer;
		icon.layer = -1;
		return layer;
	}

	private int iconWidth() {
		return round(GRID_WIDTH * scaleX);
	}

	private int iconHeight() {
		return round(GRID_HEIGHT * scaleY);
	}

	private static void uploadSlotLayer(int layer, int[] pixels) {
		glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, GRID_WIDTH, GRID_HEIGHT, 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
	}

	private static void setTextureParameters(int filter, int wrap) {
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, wrap);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, wrap);
	}

	private void cutOut(int[] pixels, int width, Rectangle bounds, Rectangle drawn, GameIcon gameIcon, Slot slot) {
		boolean unchanged = slot.kept;
		for (int y = drawn.y; unchanged && y < drawn.y + drawn.height; y++) {
			int i = y * width + drawn.x;
			int g = GameIcon.grid(drawn.x - bounds.x, y - bounds.y);
			unchanged = Arrays.equals(pixels, i, i + drawn.width, slot.behind, g, g + drawn.width);
		}
		if (!unchanged)
			paintOver(pixels, width, bounds, drawn, gameIcon, slot);

		for (int y = drawn.y; y < drawn.y + drawn.height; y++)
			System.arraycopy(slot.cut, GameIcon.grid(drawn.x - bounds.x, y - bounds.y), pixels, y * width + drawn.x, drawn.width);
	}

	private void paintOver(int[] pixels, int width, Rectangle bounds, Rectangle drawn, GameIcon gameIcon, Slot slot) {
		int[] background = slot.background;
		Arrays.fill(background, 0);
		Arrays.fill(known, false);
		for (int y = drawn.y; y < drawn.y + drawn.height; y++) {
			for (int x = drawn.x; x < drawn.x + drawn.width; x++) {
				int g = GameIcon.grid(x - bounds.x, y - bounds.y);
				int pixel = pixels[y * width + x];
				background[g] = slot.behind[g] = pixel;
				slot.cut[g] = gameIcon.stackSize[g] ? pixel : 0;
				known[g] = !gameIcon.item[g] && !gameIcon.stackSize[g];
			}
		}

		// Nearly every pixel the game shades has the same color
		int shaded = 0;
		for (int g = 0; g < GRID_SIZE; g++)
			if (known[g] && gameIcon.shadow[g])
				shadeColors[shaded++] = background[g];
		int shadow = 0;
		int mostShaded = 0;
		for (int i = 0; i < shaded && mostShaded <= shaded / 2; i++) {
			int count = 0;
			for (int j = i; j < shaded; j++)
				if (shadeColors[j] == shadeColors[i])
					count++;
			if (count > mostShaded) {
				mostShaded = count;
				shadow = shadeColors[i];
			}
		}
		if (mostShaded < shaded * .8f)
			shadow = 0;
		if (shadow != 0)
			for (int g = 0; g < GRID_SIZE; g++)
				if (gameIcon.shadow[g])
					known[g] = false;

		// Paint over the item with what surrounds it, a pixel further in each step until it's all covered
		for (boolean spreading = true; spreading; ) {
			spreading = false;
			System.arraycopy(known, 0, spread, 0, GRID_SIZE);
			for (int g = 0; g < GRID_SIZE; g++) {
				if (known[g])
					continue;
				int x = g % GRID_WIDTH;
				int y = g / GRID_WIDTH;
				int count = 0, alpha = 0, red = 0, green = 0, blue = 0;
				for (int n = 0; n < 4; n++) {
					int nx = x + NEIGHBOR_X[n];
					int ny = y + NEIGHBOR_Y[n];
					if (nx < 0 || nx >= GRID_WIDTH || ny < 0 || ny >= GRID_HEIGHT || !known[ny * GRID_WIDTH + nx])
						continue;
					int neighbor = background[ny * GRID_WIDTH + nx];
					alpha += neighbor >>> 24;
					red += neighbor >> 16 & 0xFF;
					green += neighbor >> 8 & 0xFF;
					blue += neighbor & 0xFF;
					count++;
				}
				if (count > 0) {
					background[g] = alpha / count << 24 | red / count << 16 | green / count << 8 | blue / count;
					spread[g] = true;
					spreading = true;
				}
			}
			System.arraycopy(spread, 0, known, 0, GRID_SIZE);
		}
		slot.shadow = shadow;
		slot.kept = true;
		slot.backgroundChanged = true;
	}

	private void captureOverlays(int[] pixels, int width, CutItem item) {
		// Nothing was drawn over the item while its pixels are still as they were cut out
		var slot = item.slot;
		boolean overlaid = false;
		for (int y = item.drawn.y; !overlaid && y < item.drawn.y + item.drawn.height; y++) {
			int i = y * width + item.drawn.x;
			int g = GameIcon.grid(item.drawn.x - item.bounds.x, y - item.bounds.y);
			overlaid = !Arrays.equals(pixels, i, i + item.drawn.width, slot.cut, g, g + item.drawn.width);
		}
		if (!overlaid) {
			slot.overlaysChanged |= slot.overlaid;
			slot.overlaid = false;
			return;
		}

		Arrays.fill(overlays, 0);
		Arrays.fill(captured, false);
		for (int y = item.drawn.y; y < item.drawn.y + item.drawn.height; y++) {
			for (int x = item.drawn.x; x < item.drawn.x + item.drawn.width; x++) {
				int i = y * width + x;
				int g = GameIcon.grid(x - item.bounds.x, y - item.bounds.y);
				if (item.gameIcon.stackSize[g])
					continue;
				overlays[g] = pixels[i];
				captured[g] = true;
			}
		}

		// The game draws the dragged item over the rest of the interface once it's drawn, so everything over it, and over
		// what it's dragged over, is drawn with the native icons
		boolean takeAll = draggedCuts.contains(item) || isDraggedOver(item);
		if (runeImages == null && active)
			loadRuneImages();
		var images = runeImages;
		if (!Arrays.equals(overlays, slot.overlays) || takeAll != slot.takeAll || images != slot.runeImages) {
			System.arraycopy(overlays, 0, slot.overlays, 0, GRID_SIZE);
			slot.takeAll = takeAll;
			slot.runeImages = images;
			findRunes(slot, images);
			Arrays.fill(runePixels, false);
			for (var rune : slot.runes) {
				if (takeAll && findRuneIcon(rune.image) != null) {
					for (int y = 0; y < rune.image.height; y++)
						for (int x = 0; x < rune.image.width; x++)
							runePixels[(rune.y + y) * GRID_WIDTH + rune.x + x] = rune.image.pixels[y * rune.image.width + x] >>> 24 != 0;
				}
			}
			// Fills and outlines, like those of Inventory Tags, are redrawn to fit the native icon. Fills cover the shadow too.
			var gameIcon = item.gameIcon;
			int fill = slot.fill = shapeColor(gameIcon.item, gameIcon.shadow);
			int outline = slot.outline = shapeColor(gameIcon.outline, gameIcon.shadow);
			for (int g = 0; g < GRID_SIZE; g++) {
				boolean redrawn =
					fill != 0 && (gameIcon.item[g] && overlays[g] == fill || gameIcon.shadow[g]) ||
					outline != 0 && gameIcon.outline[g] && overlays[g] == outline;
				slot.taken[g] = captured[g] && (redrawn || takeAll);
				slot.elsewhere[g] = takeAll && captured[g] && !redrawn && !runePixels[g] ? overlays[g] : 0;
			}
			slot.overlaysChanged = true;
		}

		// Everything else stays in the interface, drawn over the native icon and scaled like the rest of it
		for (int y = item.drawn.y; y < item.drawn.y + item.drawn.height; y++) {
			for (int x = item.drawn.x; x < item.drawn.x + item.drawn.width; x++) {
				if (slot.taken[GameIcon.grid(x - item.bounds.x, y - item.bounds.y)])
					pixels[y * width + x] = 0;
			}
		}
		slot.overlaysChanged |= !slot.overlaid;
		slot.overlaid = true;
		if (item.index == -1)
			return;
		for (var rune : slot.runes)
			drawRune(pixels, width, item, rune, 1);
		setColor(item.index, FILL_OFFSET, slot.fill);
		setColor(item.index, OUTLINE_OFFSET, slot.outline);
	}

	private boolean isDraggedOver(CutItem item) {
		for (var dragged : draggedCuts)
			if (dragged.drawn.intersects(item.drawn))
				return true;
		return false;
	}

	private void loadRuneImages() {
		runeImages = List.of();
		var runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		if (runes == null)
			return;

		// Named after the runes, like air_rune.png
		var paths = new HashMap<Integer, String>();
		for (int itemId : runes.getIntVals())
			paths.put(itemId, "/net/runelite/client/plugins/runepouch/" + client.getItemDefinition(itemId).getName().toLowerCase(Locale.ROOT).replace(' ', '_') + ".png");
		executor.execute(() -> {
			var images = new ArrayList<RuneImage>();
			paths.forEach((itemId, path) -> {
				try (var in = RuneLite.class.getResourceAsStream(path)) {
					if (in == null)
						return;
					BufferedImage image;
					synchronized (ImageIO.class) {
						image = ImageIO.read(in);
					}
					if (image != null && image.getWidth() <= ICON_WIDTH && image.getHeight() <= ICON_HEIGHT)
						images.add(runeImage(itemId, image));
				} catch (IOException | RuntimeException ex) {
					log.debug("Unable to load {}:", path, ex);
				}
			});
			runeImages = images;
		});
	}

	private static RuneImage runeImage(int itemId, BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
		int left = (ICON_WIDTH - width) / 2;
		int top = (ICON_HEIGHT - height) / 2;
		int[] shape = new int[ICON_WIDTH * ICON_HEIGHT];
		int opaque = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int pixel = pixels[y * width + x];
				if (pixel >>> 24 == 0xFF)
					opaque++;
				// The black ring around the rune is its outline, which is drawn around the native one again
				boolean outline = pixel == 0xFF000000 && (
					isClear(pixels, width, height, x - 1, y) || isClear(pixels, width, height, x + 1, y) ||
					isClear(pixels, width, height, x, y - 1) || isClear(pixels, width, height, x, y + 1));
				if (pixel >>> 24 != 0 && !outline)
					shape[(top + y) * ICON_WIDTH + left + x] = pixel;
			}
		}

		int[] offsets = new int[opaque];
		int[] colors = new int[opaque];
		for (int i = 0, y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (pixels[y * width + x] >>> 24 == 0xFF) {
					offsets[i] = y * GRID_WIDTH + x;
					colors[i++] = pixels[y * width + x];
				}
			}
		}
		long key = GameIcon.hash(GameIcon.hash(0xCBF29CE484222325L, itemId, 1), shape);
		return new RuneImage(itemId, width, height, pixels, offsets, colors, left, top, shape, key);
	}

	private static boolean isClear(int[] pixels, int width, int height, int x, int y) {
		return x < 0 || y < 0 || x >= width || y >= height || pixels[y * width + x] >>> 24 == 0;
	}

	private void findRunes(Slot slot, @Nullable List<RuneImage> images) {
		slot.runes.clear();
		if (images == null)
			return;
		for (var image : images)
			for (int y = 0; y + image.height <= GRID_HEIGHT; y++)
				for (int x = 0; x + image.width <= GRID_WIDTH; x++)
					if (isDrawnAt(image, y * GRID_WIDTH + x))
						slot.runes.add(new RuneMatch(image, x, y));
	}

	private boolean isDrawnAt(RuneImage image, int at) {
		for (int i = 0; i < image.offsets.length; i++) {
			int g = at + image.offsets[i];
			if (!captured[g] || overlays[g] != image.colors[i])
				return false;
		}
		return true;
	}

	private void drawRune(int[] pixels, int width, CutItem item, RuneMatch rune, float opacity) {
		var icon = findRuneIcon(rune.image);
		if (icon == null || icon.layer == -1 || itemCount == MAX_ITEMS)
			return;
		icon.frame = frame;

		// RuneLite's image is taken out once the native rune can be drawn in its place
		var image = rune.image;
		int left = item.bounds.x - MARGIN + rune.x;
		int top = item.bounds.y - MARGIN + rune.y;
		var drawn = new Rectangle(left, top, image.width, image.height).intersection(item.drawn);
		for (int y = drawn.y; y < drawn.y + drawn.height; y++)
			for (int x = drawn.x; x < drawn.x + drawn.width; x++)
				if (image.pixels[(y - top) * image.width + x - left] >>> 24 != 0)
					pixels[y * width + x] = 0;
		itemSlots[itemCount] = NO_SLOT;
		putQuad(new Rectangle(left - image.left, top - image.top, ICON_WIDTH, ICON_HEIGHT), drawn, icon.layer, NO_SLOT.layer, opacity, 0, 0);
	}

	@Nullable
	private Icon findRuneIcon(RuneImage image) {
		var icon = icons.get(image.key);
		if (icon == null) {
			icon = loadIcon(image.key);
			icons.put(image.key, icon);
		} else if (icon.uncached) {
			icon.uncached = false;
			var item = client.getItemDefinition(image.itemId);
			var mesh = loadMesh(item);
			if (mesh == null)
				icon.failed = true;
			else
				drawIcon(icon, new Layer[] { new Layer(mesh, item.getXan2d(), item.getYan2d(), item.getZan2d(), image.shape, 1, false) }, image.itemId, image.key);
		}
		return icon.failed ? null : icon;
	}

	private int shapeColor(boolean[] shape, boolean[] shared) {
		// Boyer-Moore majority vote
		int color = 0, lead = 0, pixels = 0;
		for (int g = 0; g < GRID_SIZE; g++) {
			if (!captured[g] || !shape[g] || shared[g])
				continue;
			pixels++;
			if (lead == 0)
				color = overlays[g];
			lead += overlays[g] == color ? 1 : -1;
		}
		if (color == 0)
			return 0;

		int covered = 0;
		for (int g = 0; g < GRID_SIZE; g++) {
			if (!captured[g] || overlays[g] != color || shared[g])
				continue;
			if (!shape[g])
				return 0;
			covered++;
		}
		return covered >= MIN_SHAPE_COVERAGE * pixels ? color : 0;
	}

	// The game's icon is taken out again, and the native one drawn over the other items
	private void drawDraggedItem(int[] pixels, int width, CutItem item) {
		int shadow = 0;
		for (int y = item.drawn.y; y < item.drawn.y + item.drawn.height; y++) {
			for (int x = item.drawn.x; x < item.drawn.x + item.drawn.width; x++) {
				int i = y * width + x;
				int g = GameIcon.grid(x - item.bounds.x, y - item.bounds.y);
				if (item.gameIcon.stackSize[g])
					continue;
				if (item.gameIcon.shadow[g])
					shadow = pixels[i];
				pixels[i] = 0;
			}
		}
		if (itemCount == MAX_ITEMS)
			return;

		var slot = item.slot;
		int index = itemCount;
		itemSlots[index] = slot;
		putQuad(item.bounds, item.drawn, item.iconLayer, slot.layer, item.opacity, item.border, shadow);
		if (!slot.overlaid)
			return;
		setColor(index, FILL_OFFSET, slot.fill);
		setColor(index, OUTLINE_OFFSET, slot.outline);
		for (var rune : slot.runes)
			drawRune(pixels, width, item, rune, item.opacity);
	}
}
