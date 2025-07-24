package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.function.Consumer;
import javax.inject.Inject;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.ShaderException;
import rs117.hd.opengl.shader.ShaderProgram;
import rs117.hd.opengl.shader.ShaderTemplate;
import rs117.hd.utils.ShaderRecompile;

import static org.lwjgl.opengl.GL33C.*;

@Slf4j
@Singleton
public class ShaderOverlay<T extends ShaderOverlay.Shader> extends Overlay {
	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	@Inject
	public T shader;

	@Getter
	@Setter
	private boolean borderless;

	@Getter
	private boolean centered;

	@Setter
	private boolean maintainAspectRatio;

	private boolean movable = true;
	private boolean snappable = true;

	private boolean initialized;
	private final Dimension initialSize = new Dimension(256, 256);
	private long becameHiddenAt;
	private boolean isHidden = true;
	private boolean skipNextGetPreferredLocation;
	private final float[] aspectRatioRoundingError = { 0, 0 };

	public static class Shader extends ShaderProgram {
		protected final Uniform4f uniTransform = addUniform4f("transform");

		public Shader(Consumer<ShaderTemplate> templateConsumer) {
			super(template -> {
				template
					.add(GL_VERTEX_SHADER, "overlays/overlay_vert.glsl")
					.add(GL_FRAGMENT_SHADER, "overlays/overlay_frag.glsl");
				templateConsumer.accept(template);
			});
		}
	}

	public ShaderOverlay() {
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setResizable(true);
	}

	public void initialize() {
		try {
			shader.compile(plugin.getShaderIncludes());
		} catch (Exception ex) {
			log.error("Failed to compile shader:", ex);
			return;
		}
		overlayManager.add(this);
		eventBus.register(this);
		initialized = true;
	}

	public void destroy() {
		initialized = false;
		isHidden = true;
		becameHiddenAt = 0;
		overlayManager.remove(this);
		eventBus.unregister(this);
		shader.destroy();
	}

	public void setActive(boolean activate) {
		if (activate == initialized)
			return;

		clientThread.invoke(() -> {
			if (activate) {
				initialize();
			} else {
				destroy();
			}
		});
	}

	@Override
	protected void setMovable(boolean movable) {
		super.setMovable(movable);
		this.movable = movable;
	}

	@Override
	protected void setSnappable(boolean snappable) {
		super.setSnappable(snappable);
		this.snappable = snappable;
	}

	protected void setCentered(boolean centered) {
		this.centered = centered;
		setPosition(centered ? OverlayPosition.DYNAMIC : OverlayPosition.TOP_LEFT);
		super.setMovable(movable);
		super.setSnappable(snappable);
	}

	public boolean shouldMaintainAspectRatio() {
		return maintainAspectRatio;
	}

	protected void setInitialSize(int width, int height) {
		initialSize.setSize(width, height);
	}

	@Override
	public Dimension getPreferredSize() {
		var size = super.getPreferredSize();
		return size != null ? size : new Dimension(initialSize);
	}

	private boolean isLoggedIn() {
		return client != null && client.getGameState().getState() >= GameState.LOGGED_IN.getState();
	}

	private boolean shouldCenter() {
		// If centered & not at a custom position & not in a snap corner & currently logged in to interact with overlays
		return centered && getPreferredLocation() == null && getPreferredPosition() == null && isLoggedIn();
	}

	@Override
	public Rectangle getBounds() {
		var bounds = super.getBounds();
		if (shouldCenter()) {
			// Recenter the overlay when centered
			bounds.setSize(getPreferredSize());
			bounds.x = (client.getCanvasWidth() - bounds.width) / 2;
			bounds.y = (client.getCanvasHeight() - bounds.height) / 2;
		}
		return bounds;
	}

	@Override
	public Point getPreferredLocation() {
		// Hacky way to prevent RuneLite from updating the location during resizing
		if (skipNextGetPreferredLocation) {
			skipNextGetPreferredLocation = false;
			return null;
		}
		return super.getPreferredLocation();
	}

	@Override
	public void setPreferredLocation(Point preferredLocation) {
		resetHiddenTimer();
		super.setPreferredLocation(preferredLocation);
	}

	@Override
	public void setPreferredSize(Dimension size) {
		if (size != null) {
			resetHiddenTimer();
			var cursor = clientUI.getCurrentCursor().getType();

			if (shouldCenter()) {
				// Take over resizing when centered
				var prev = getPreferredSize();
				var mouse = client.getMouseCanvasPosition();
				if (size.width != prev.width)
					size.width = 2 * mouse.getX() - client.getCanvasWidth();
				if (size.height != prev.height)
					size.height = 2 * mouse.getY() - client.getCanvasHeight();

				switch (cursor) {
					case Cursor.NW_RESIZE_CURSOR:
						size.height *= -1;
					case Cursor.W_RESIZE_CURSOR:
					case Cursor.SW_RESIZE_CURSOR:
						size.width *= -1;
						break;
					case Cursor.N_RESIZE_CURSOR:
					case Cursor.NE_RESIZE_CURSOR:
						size.height *= -1;
						break;
				}
			}

			int minWidth, minHeight;
			minWidth = minHeight = getMinimumSize();

			if (shouldMaintainAspectRatio()) {
				float aspectRatio = (float) initialSize.width / initialSize.height;
				if (aspectRatio > 1) {
					minWidth = Math.round(minHeight * aspectRatio);
				} else {
					minHeight = Math.round(minWidth / aspectRatio);
				}
				var prevSize = getPreferredSize();

				boolean resizingWidth = true;
				// This could be further improved by keeping track of the size at the start of the resize instead of the previous size
				int widthChange = size.width - prevSize.width;
				int heightChange = size.height - prevSize.height;
				switch (cursor) {
					case Cursor.N_RESIZE_CURSOR:
					case Cursor.S_RESIZE_CURSOR:
						resizingWidth = false;
						break;
					case Cursor.NW_RESIZE_CURSOR:
					case Cursor.NE_RESIZE_CURSOR:
					case Cursor.SW_RESIZE_CURSOR:
					case Cursor.SE_RESIZE_CURSOR:
						resizingWidth = widthChange > heightChange;
						break;
				}

				if (resizingWidth) {
					size.height = Math.round(size.width / aspectRatio);
					heightChange = size.height - prevSize.height;
				} else {
					size.width = Math.round(size.height * aspectRatio);
					widthChange = size.width - prevSize.width;
				}

				var loc = getPreferredLocation();
				if (loc != null) {
					// Since we'll be skipping RuneLite's location adjustment, we must add it in ourselves
					switch (cursor) {
						case Cursor.N_RESIZE_CURSOR:
						case Cursor.NE_RESIZE_CURSOR:
							if (!resizingWidth)
								loc.y -= heightChange;
							break;
						case Cursor.W_RESIZE_CURSOR:
						case Cursor.SW_RESIZE_CURSOR:
							if (resizingWidth)
								loc.x -= widthChange;
							break;
						case Cursor.NW_RESIZE_CURSOR:
							if (resizingWidth) {
								loc.x -= widthChange;
							} else {
								loc.y -= heightChange;
							}
							break;
					}

					// Adjust the location along the dimension which was adjusted to keep the same aspect ratio
					float shouldSubtract;
					int willSubtract;
					if (resizingWidth) {
						shouldSubtract = heightChange / 2f + aspectRatioRoundingError[1];
						willSubtract = (int) shouldSubtract;
						aspectRatioRoundingError[1] = shouldSubtract - willSubtract;
						loc.y -= willSubtract;
					} else {
						shouldSubtract = widthChange / 2f + aspectRatioRoundingError[0];
						willSubtract = (int) shouldSubtract;
						aspectRatioRoundingError[0] = shouldSubtract - willSubtract;
						loc.x -= willSubtract;
					}

					setPreferredLocation(loc);
					skipNextGetPreferredLocation = true;
				}
			}

			size.width = Math.max(minWidth, size.width);
			size.height = Math.max(minHeight, size.height);
		}

		super.setPreferredSize(size);
	}

	@Subscribe
	public void onShaderRecompile(ShaderRecompile event) throws ShaderException, IOException {
		shader.compile(event.includes);
	}

	public boolean isHidden() {
		return !initialized || !shader.isValid() || !isLoggedIn();
	}

	public boolean isManageable() {
		return isMovable() || isResizable();
	}

	private void updateTransform(int canvasWidth, int canvasHeight) {
		assert shader.isActive();
		var bounds = getBounds();
		// Calculate translation and scale in NDC
		float[] rect = { bounds.x + 1, bounds.y + 1, bounds.width - 1, bounds.height - 1 };
		rect[0] += rect[0] + rect[2];
		rect[1] += rect[1] + rect[3];
		for (int i = 0; i < 2; i++) {
			rect[i * 2] /= canvasWidth;
			rect[i * 2 + 1] /= canvasHeight;
			rect[i] -= 1;
		}
		rect[1] *= -1;
		shader.uniTransform.set(rect);
	}

	public void render(int canvasWidth, int canvasHeight) {
		if (isHidden())
			return;

		shader.use();
		updateTransform(canvasWidth, canvasHeight);
		updateUniforms();
		render();
	}

	protected void updateUniforms() {}

	protected void render() {
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
	}

	private void resetHiddenTimer() {
		becameHiddenAt = System.currentTimeMillis();
	}

	private long getTimeSinceHiding() {
		return System.currentTimeMillis() - becameHiddenAt;
	}

	private boolean getUpdatedHiddenState() {
		boolean isHidden = isHidden();
		if (isHidden && !this.isHidden)
			resetHiddenTimer();
		return this.isHidden = isHidden;
	}

	@Override
	public Dimension render(Graphics2D g) {
		var bounds = getBounds();
		if (getUpdatedHiddenState()) {
			// When the overlay is hidden, keep it manageable for a minute by not returning null
			if (!isManageable() || getTimeSinceHiding() >= 60000)
				return null;
		} else if (!borderless) {
			g.setColor(Color.BLACK);
			g.drawRect(0, 0, bounds.width, bounds.height);
		}
		return bounds.getSize();
	}
}
