package rs117.hd.overlays;

import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
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

	private boolean movable = true;
	private boolean snappable = true;

	private boolean initialized;
	private final Dimension initialSize = new Dimension(256, 256);

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
	public void setSnappable(boolean snappable) {
		super.setSnappable(snappable);
		this.snappable = snappable;
	}

	protected void setCentered(boolean centered) {
		this.centered = centered;
		setPosition(centered ? OverlayPosition.DYNAMIC : OverlayPosition.TOP_LEFT);
		super.setMovable(movable);
		super.setSnappable(snappable);
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
	public void setPreferredSize(Dimension size) {
		if (size != null && shouldCenter()) {
			// Take over resizing when centered
			var prev = getPreferredSize();
			var mouse = client.getMouseCanvasPosition();
			if (size.width != prev.width)
				size.width = 2 * mouse.getX() - client.getCanvasWidth();
			if (size.height != prev.height)
				size.height = 2 * mouse.getY() - client.getCanvasHeight();

			switch (clientUI.getCurrentCursor().getType()) {
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

			int min = getMinimumSize();
			size.width = Math.max(min, size.width);
			size.height = Math.max(min, size.height);
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

	@Override
	public Dimension render(Graphics2D g) {
		if (isHidden())
			return null;

		if (!borderless) {
			var bounds = getBounds();
			g.setColor(Color.BLACK);
			g.drawRect(0, 0, bounds.width, bounds.height);
		}

		return getBounds().getSize();
	}
}
