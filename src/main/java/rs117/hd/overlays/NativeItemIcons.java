package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
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
	private static final int MAX_NEW_ITEMS_PER_FRAME = 16;
	// Native icons can reach a little past the game's icons
	private static final int MARGIN = 2;
	private static final int GRID_WIDTH = ICON_WIDTH + 2 * MARGIN;
	private static final int GRID_HEIGHT = ICON_HEIGHT + 2 * MARGIN;
	private static final int GRID_SIZE = GRID_WIDTH * GRID_HEIGHT;
	private static final int LAYERS_PER_ITEM = 2;
	private static final float MIN_SHAPE_COVERAGE = .9f;
	private static final int[] VERTEX_ATTRIBUTE_SIZES = { 2, 2, 2, 1, 1, 4, 4, 4 };
	private static final int FLOATS_PER_VERTEX = 20;
	private static final int FLOATS_PER_ITEM = FLOATS_PER_VERTEX * 6;
	private static final int SHADOW_OFFSET = 8;
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
	}

	@RequiredArgsConstructor
	private static class Layer {
		final ItemIconRasterizer.Mesh mesh;
		final int pitch, yaw, roll;
		final int[] gameIcon;
		final int border;
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
				removeDraggedItem(buffer.getPixels(), buffer.getWidth(), item);
			draggedCuts.clear();
			return null;
		}
	}

	private static final Icon PENDING = new Icon();
	private static final GameIcon UNKNOWN = new GameIcon(-1, 0, new int[ICON_WIDTH * ICON_HEIGHT], new int[ICON_WIDTH * ICON_HEIGHT]);

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
	private int vao;
	private int vbo;
	private int texIcons;
	private int texIconSurroundings;
	private int texBackgrounds;

	private float scaleX;
	private float scaleY;
	private double brightness;
	private Filepath cacheFolder;
	private ItemIconCache cache;

	private final Map<Long, Icon> icons = new HashMap<>();
	private final ArrayDeque<Integer> freeLayers = new ArrayDeque<>();
	private final Map<Long, GameIcon> gameIcons = new LinkedHashMap<>(MAX_GAME_ICONS, .75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, GameIcon> eldest) {
			return size() > MAX_GAME_ICONS;
		}
	};
	private final Map<Long, Integer> stackModels = new HashMap<>();
	private final Map<Long, Integer> stackModelSearches = new HashMap<>();
	private final Map<Integer, Item[]> containers = new HashMap<>();

	private int newItemsThisFrame;
	private int itemCount;
	private final Set<Rectangle> cutThisFrame = new HashSet<>();
	private final List<CutItem> uncaptured = new ArrayList<>();
	private final List<WidgetItem> draggedItems = new ArrayList<>();
	private final List<CutItem> draggedCuts = new ArrayList<>();
	private final int[] backgrounds = new int[MAX_ITEMS * LAYERS_PER_ITEM * GRID_SIZE];
	private final FloatBuffer vertices = BufferUtils.createFloatBuffer(MAX_ITEMS * FLOATS_PER_ITEM);
	private final boolean[] known = new boolean[GRID_SIZE];
	private final boolean[] spread = new boolean[GRID_SIZE];
	private final int[] overlays = new int[GRID_SIZE];
	private final boolean[] captured = new boolean[GRID_SIZE];

	private int[] palette;
	private double paletteBrightness;

	public NativeItemIcons() {
		showOnInventory();
		showOnBank();
		showOnEquipment();
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
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, GRID_WIDTH, GRID_HEIGHT, MAX_ITEMS * LAYERS_PER_ITEM, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
		setTextureParameters(GL_NEAREST, GL_CLAMP_TO_EDGE);

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
		containers.clear();
		scaleX = scaleY = 0;
	}

	@Subscribe
	public void onShaderRecompile(ShaderRecompile event) throws ShaderException, IOException {
		shader.compile(event.includes);
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event) {
		newItemsThisFrame = 0;
		itemCount = 0;
		vertices.clear();
		cutThisFrame.clear();
		uncaptured.clear();
		draggedItems.clear();
		draggedCuts.clear();
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event) {
		if (showOnInterface(event.getGroupId())) {
			// Re-add so the overlay manager picks up the new interface
			overlayManager.remove(this);
			overlayManager.remove(overlayCapture);
			overlayManager.add(this);
			overlayManager.add(overlayCapture);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		containers.put(event.getContainerId(), event.getItemContainer().getItems());
	}

	private boolean showOnInterface(int groupId) {
		if (groupId == -1 || getDrawHooks().contains(groupId << 16 | 0xFFFF))
			return false;
		drawAfterInterface(groupId);
		overlayCapture.showOnInterface(groupId);
		return true;
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
		if (isDragged(widgetItem)) {
			draggedItems.add(widgetItem);
		} else {
			cutOut(widgetItem);
		}
	}

	private boolean isDragged(WidgetItem widgetItem) {
		return widgetItem.getWidget() == client.getDraggedWidget();
	}

	private void cutOut(WidgetItem widgetItem) {
		var icon = findIcon(widgetItem);
		if (icon == null || icon.layer == -1 || itemCount == MAX_ITEMS)
			return;

		var widget = widgetItem.getWidget();
		boolean dragged = isDragged(widgetItem);
		var bounds = widgetItem.getDraggingCanvasBounds() != null ? widgetItem.getDraggingCanvasBounds() : widgetItem.getCanvasBounds();
		var visible = bounds.intersection(widget.getParent().getBounds());
		// Some interfaces report their items twice
		if (visible.isEmpty() || !cutThisFrame.add(bounds))
			return;

		var buffer = client.getBufferProvider();
		// Partly visible items stay clipped like the interface clips them
		var drawn = !visible.equals(bounds) ? visible :
			new Rectangle(bounds.x - MARGIN, bounds.y - MARGIN, GRID_WIDTH, GRID_HEIGHT)
				.intersection(new Rectangle(buffer.getWidth(), buffer.getHeight()));

		// The game draws dragged items half transparent
		float opacity = (dragged ? 128 : 256 - widget.getOpacity()) / 256f;
		var gameIcon = getGameIcon(widgetItem.getId(), widgetItem.getQuantity(), widget.getItemQuantityMode(), widget.getBorderType(), true);
		int offset = itemCount * LAYERS_PER_ITEM * GRID_SIZE;
		int shadow = cutOut(buffer.getPixels(), buffer.getWidth(), bounds, drawn, gameIcon, offset);
		Arrays.fill(backgrounds, offset + GRID_SIZE, offset + 2 * GRID_SIZE, 0);
		var cutItem = new CutItem(itemCount, bounds, drawn, gameIcon);
		uncaptured.add(cutItem);
		if (dragged)
			draggedCuts.add(cutItem);

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
				.put(icon.layer)
				.put(itemCount * LAYERS_PER_ITEM)
				.put(opacity)
				.put(widget.getBorderType());
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
		} else if (icon.uncached) {
			int modelItemId = findModelItem(itemId, quantity, border, gameIcon);
			if (modelItemId == -1)
				return null;
			icon.uncached = false;
			if (modelItemId == -2) {
				icon.failed = true;
				var cache = this.cache;
				executor.execute(() -> cache.save(gameIcon.key, null, null));
			} else {
				drawIcon(icon, modelItemId, border, gameIcon.key);
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
		for (var items : containers.values()) {
			for (var item : items) {
				if (icons.size() >= MAX_ICONS - MAX_ITEMS)
					return;
				if (item.getId() != -1 && findIcon(item.getId(), item.getQuantity(), ItemQuantityMode.NEVER, 1, false) == PENDING)
					return;
			}
		}
	}

	@Nullable
	private GameIcon getGameIcon(int itemId, int quantity, int quantityMode, int border, boolean visible) {
		long key = (long) quantity << 24 | (long) itemId << 4 | quantityMode << 2 | border;
		if (gameIcons.containsKey(key))
			return gameIcons.get(key);
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
					if (!rasterizer.lineUpWith(layer.gameIcon, palette)) {
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
			data = data.cloneTextures();
			for (int i = 0; i < find.length; i++)
				data.retexture(find[i], replace[i]);
		}

		// Lit like the game lights item models
		var model = data.light(item.getAmbient() + 64, item.getContrast() + 768, -50, -10, -50);
		var mesh = ItemIconRasterizer.Mesh.copyOf(model, client.getTextureProvider());
		int[] gameIcon = gamePixels(itemId, quantity, 0, ItemQuantityMode.NEVER, noted);
		if (mesh == null || gameIcon == null)
			return null;
		return new Layer(mesh, item.getXan2d(), item.getYan2d(), item.getZan2d(), gameIcon, border);
	}

	private synchronized int[] getPalette(double brightness) {
		if (palette == null || paletteBrightness != brightness) {
			palette = ItemIconRasterizer.palette(brightness);
			paletteBrightness = brightness;
		}
		return palette;
	}

	public void render(int[] uiResolution, int[] actualUiResolution) {
		if (!active)
			return;

		boolean full = false;
		glActiveTexture(TEXTURE_UNIT_ITEM_ICON_SURROUNDINGS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texIconSurroundings);
		glActiveTexture(TEXTURE_UNIT_ITEM_ICONS);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texIcons);
		for (var icon : icons.values()) {
			if (icon.pixels == null)
				continue;
			if (icon.layer == -1) {
				if (freeLayers.isEmpty()) {
					full = true;
					continue;
				}
				icon.layer = freeLayers.pop();
			}
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, icon.layer, iconWidth(), iconHeight(), 1, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, icon.pixels);
			glActiveTexture(TEXTURE_UNIT_ITEM_ICON_SURROUNDINGS);
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, icon.layer, iconWidth(), iconHeight(), 1, GL_RED, GL_FLOAT, icon.surroundings);
			glActiveTexture(TEXTURE_UNIT_ITEM_ICONS);
			icon.pixels = null;
			icon.surroundings = null;
		}

		if (itemCount > 0) {
			glActiveTexture(TEXTURE_UNIT_ITEM_BACKGROUNDS);
			glBindTexture(GL_TEXTURE_2D_ARRAY, texBackgrounds);
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, GRID_WIDTH, GRID_HEIGHT, itemCount * LAYERS_PER_ITEM, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, backgrounds);

			shader.use();
			glBindVertexArray(vao);
			glBindBuffer(GL_ARRAY_BUFFER, vbo);
			glBufferSubData(GL_ARRAY_BUFFER, 0, vertices.flip());
			glEnable(GL_BLEND);
			glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
			glDrawArrays(GL_TRIANGLES, 0, itemCount * 6);
		}

		if (full)
			clearIcons();

		// Rounded, so small window resizes don't invalidate the icons kept on disk
		float newScaleX = round(8f * actualUiResolution[0] / uiResolution[0]) / 8f;
		float newScaleY = round(8f * actualUiResolution[1] / uiResolution[1]) / 8f;
		double newBrightness = client.getTextureProvider().getBrightness();
		if (newScaleX != scaleX || newScaleY != scaleY || newBrightness != brightness) {
			// The game's icons depend on the brightness too
			if (newBrightness != brightness)
				gameIcons.clear();
			scaleX = newScaleX;
			scaleY = newScaleY;
			brightness = newBrightness;
			cache = new ItemIconCache(cacheFolder, scaleX, scaleY, brightness, iconWidth(), iconHeight());
			executor.execute(cache::markUsed);
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

		if (scaleX * scaleY > 1)
			prepareIcons();
	}

	private void clearIcons() {
		icons.clear();
		freeLayers.clear();
		for (int i = 0; i < MAX_ICONS; i++)
			freeLayers.push(i);
	}

	private int iconWidth() {
		return round(GRID_WIDTH * scaleX);
	}

	private int iconHeight() {
		return round(GRID_HEIGHT * scaleY);
	}

	private static void setTextureParameters(int filter, int wrap) {
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, filter);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, wrap);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, wrap);
	}

	private int cutOut(int[] pixels, int width, Rectangle bounds, Rectangle drawn, GameIcon gameIcon, int offset) {
		Arrays.fill(backgrounds, offset, offset + GRID_SIZE, 0);
		Arrays.fill(known, false);
		for (int y = drawn.y; y < drawn.y + drawn.height; y++) {
			for (int x = drawn.x; x < drawn.x + drawn.width; x++) {
				int i = y * width + x;
				int g = GameIcon.grid(x - bounds.x, y - bounds.y);
				backgrounds[offset + g] = pixels[i];
				known[g] = !gameIcon.item[g] && !gameIcon.stackSize[g];
				if (!gameIcon.stackSize[g])
					pixels[i] = 0;
			}
		}

		// Every pixel the game shades has the same color
		int shadow = 0;
		boolean first = true;
		for (int g = 0; g < GRID_SIZE; g++) {
			if (!known[g] || !gameIcon.shadow[g])
				continue;
			int color = backgrounds[offset + g];
			if (first) {
				shadow = color;
				first = false;
			} else if (color != shadow) {
				shadow = 0;
				break;
			}
		}
		if (shadow != 0)
			for (int g = 0; g < GRID_SIZE; g++)
				if (gameIcon.shadow[g])
					known[g] = false;

		// Paint over the item with what surrounds it
		for (int step = 0; step < 3; step++) {
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
					int neighbor = backgrounds[offset + ny * GRID_WIDTH + nx];
					alpha += neighbor >>> 24;
					red += neighbor >> 16 & 0xFF;
					green += neighbor >> 8 & 0xFF;
					blue += neighbor & 0xFF;
					count++;
				}
				if (count > 0) {
					backgrounds[offset + g] = alpha / count << 24 | red / count << 16 | green / count << 8 | blue / count;
					spread[g] = true;
				}
			}
			System.arraycopy(spread, 0, known, 0, GRID_SIZE);
		}
		return shadow;
	}

	private void captureOverlays(int[] pixels, int width, CutItem item) {
		Arrays.fill(captured, false);
		for (int y = item.drawn.y; y < item.drawn.y + item.drawn.height; y++) {
			for (int x = item.drawn.x; x < item.drawn.x + item.drawn.width; x++) {
				int i = y * width + x;
				int g = GameIcon.grid(x - item.bounds.x, y - item.bounds.y);
				if (item.gameIcon.stackSize[g])
					continue;
				overlays[g] = pixels[i];
				captured[g] = true;
				pixels[i] = 0;
			}
		}

		// Fills and outlines, like those of Inventory Tags, are redrawn to fit the native icon. Fills cover the shadow too.
		var gameIcon = item.gameIcon;
		int fill = shapeColor(gameIcon.item, gameIcon.shadow);
		int outline = shapeColor(gameIcon.outline, gameIcon.shadow);
		setColor(item.index, FILL_OFFSET, fill);
		setColor(item.index, OUTLINE_OFFSET, outline);

		int offset = (item.index * LAYERS_PER_ITEM + 1) * GRID_SIZE;
		for (int g = 0; g < GRID_SIZE; g++) {
			boolean redrawn =
				fill != 0 && (gameIcon.item[g] && overlays[g] == fill || gameIcon.shadow[g]) ||
				outline != 0 && gameIcon.outline[g] && overlays[g] == outline;
			backgrounds[offset + g] = captured[g] && !redrawn ? overlays[g] : 0;
		}
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

	private void removeDraggedItem(int[] pixels, int width, CutItem item) {
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
		setColor(item.index, SHADOW_OFFSET, shadow);
	}
}
