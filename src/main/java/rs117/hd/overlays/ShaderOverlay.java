/*
 * Copyright (c) 2025, Hooder <ahooder@protonmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
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
import static rs117.hd.utils.MathUtils.*;

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

	@Getter
	private boolean fullscreen;

	private boolean movable = true;
	private boolean snappable = true;

	private boolean initialized;
	private final Dimension initialSize = new Dimension(256, 256);
	private long becameHiddenAt;
	private boolean isHidden = true;
	private boolean skipNextGetPreferredLocation;
	private boolean isProbablyStartingResize;
	private Rectangle resizeStartingBounds = new Rectangle();
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

	protected void setFullscreen(boolean fullscreen) {
		this.fullscreen = fullscreen;
		if (fullscreen) {
			setPosition(OverlayPosition.DYNAMIC);
		} else {
			setPosition(OverlayPosition.TOP_LEFT);
		}
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
		if (isFullscreen())
			return null;
		var size = super.getPreferredSize();
		return size != null ? size : new Dimension(initialSize);
	}

	private boolean isLoggedIn() {
		return client != null && client.getGameState().getState() >= GameState.LOGGED_IN.getState();
	}

	private boolean shouldKeepCentered() {
		// If centered & not at a custom position & not in a snap corner & currently logged in to interact with overlays
		return centered && getPreferredLocation() == null && getPreferredPosition() == null && isLoggedIn();
	}

	@Override
	public Rectangle getBounds() {
		var bounds = super.getBounds();
		if (shouldKeepCentered()) {
			bounds.setSize(getPreferredSize());
			bounds.x = (client.getCanvasWidth() - bounds.width) / 2;
			bounds.y = (client.getCanvasHeight() - bounds.height) / 2;
		}
		if (isProbablyStartingResize) {
			isProbablyStartingResize = false;
			resizeStartingBounds = new Rectangle(bounds);
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
		// Reset the hide timer while resizing
		if (!resizeStartingBounds.isEmpty())
			resetHideTimer();
		super.setPreferredLocation(preferredLocation);
	}

	@Override
	public boolean isResizable() {
		isProbablyStartingResize = true;
		return super.isResizable();
	}

	@Override
	public void setPreferredSize(Dimension size) {
		if (size != null) {
			// Reset the hide timer while resizing
			if (!resizeStartingBounds.isEmpty())
				resetHideTimer();

			int minWidth, minHeight;
			minWidth = minHeight = getMinimumSize();
			int maxWidth = client.getCanvasWidth() - 1;
			int maxHeight = client.getCanvasHeight() - 1;
			var cursor = clientUI.getCurrentCursor().getType();

			// Take over resizing when centered
			if (shouldKeepCentered()) {
				var prev = getPreferredSize();
				var mouse = client.getMouseCanvasPosition();
				if (size.width != prev.width)
					size.width = 2 * mouse.getX() - maxWidth;
				if (size.height != prev.height)
					size.height = 2 * mouse.getY() - maxHeight;

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

				size.width = clamp(size.width, minWidth, maxWidth);
				size.height = clamp(size.height, minHeight, maxHeight);
			}

			if (shouldMaintainAspectRatio()) {
				float aspectRatio = (float) initialSize.width / initialSize.height;
				if (aspectRatio > 1) {
					minWidth = round(minHeight * aspectRatio);
					maxHeight = round(maxWidth / aspectRatio);
				} else {
					minHeight = round(minWidth / aspectRatio);
					maxWidth = round(maxHeight * aspectRatio);
				}

				boolean resizingHeight = cursor == Cursor.N_RESIZE_CURSOR || cursor == Cursor.S_RESIZE_CURSOR;
				if (resizingHeight) {
					size.width = round(size.height * aspectRatio);
				} else {
					size.height = round(size.width / aspectRatio);
				}

				size.width = clamp(size.width, minWidth, maxWidth);
				size.height = clamp(size.height, minHeight, maxHeight);

				var loc = getPreferredLocation();
				if (loc != null) {
					var prevSize = getPreferredSize();
					int widthChange = size.width - prevSize.width;
					int heightChange = size.height - prevSize.height;

					// Since we'll be skipping RuneLite's location adjustment, we must add it in ourselves
					switch (cursor) {
						case Cursor.N_RESIZE_CURSOR:
						case Cursor.NE_RESIZE_CURSOR:
							loc.y -= heightChange;
							break;
						case Cursor.W_RESIZE_CURSOR:
						case Cursor.SW_RESIZE_CURSOR:
							loc.x -= widthChange;
							break;
						case Cursor.NW_RESIZE_CURSOR:
							loc.x -= widthChange;
							loc.y -= heightChange;
							break;
					}

					// If adjusting along one dimension, automatically adjust the other dimension to maintain the aspect ratio
					switch (cursor) {
						case Cursor.N_RESIZE_CURSOR:
						case Cursor.S_RESIZE_CURSOR:
						case Cursor.E_RESIZE_CURSOR:
						case Cursor.W_RESIZE_CURSOR:
							if (resizingHeight) {
								float shouldSubtract = widthChange / 2f + aspectRatioRoundingError[0];
								int willSubtract = (int) shouldSubtract;
								aspectRatioRoundingError[0] = shouldSubtract - willSubtract;
								loc.x -= willSubtract;
							} else {
								float shouldSubtract = heightChange / 2f + aspectRatioRoundingError[1];
								int willSubtract = (int) shouldSubtract;
								aspectRatioRoundingError[1] = shouldSubtract - willSubtract;
								loc.y -= willSubtract;
							}
							break;
					}

					// Update the location and skip the next location update to ignore RuneLite's resize location change
					setPreferredLocation(loc);
					skipNextGetPreferredLocation = true;
				}
			}
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

	private void updateTransform() {
		assert shader.isActive();
		if (isFullscreen()) {
			shader.uniTransform.set(0, 0, 1, 1);
		} else {
			int[] resolution = plugin.getUiResolution();
			if (resolution == null)
				return;
			var bounds = getBounds();
			// Calculate translation and scale in NDC
			float[] rect = { bounds.x + 1, bounds.y + 1, bounds.width - 1, bounds.height - 1 };
			rect[0] += rect[0] + rect[2];
			rect[1] += rect[1] + rect[3];
			for (int i = 0; i < 2; i++) {
				rect[i * 2] /= resolution[0];
				rect[i * 2 + 1] /= resolution[1];
				rect[i] -= 1;
			}
			rect[1] *= -1;
			shader.uniTransform.set(rect);
		}
	}

	public void render() {
		if (isHidden())
			return;

		shader.use();
		updateTransform();
		updateUniforms();
		renderShader();
	}

	protected void updateUniforms() {}

	protected void renderShader() {
		glEnable(GL_BLEND);
		glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
		if (fullscreen) {
			glBindVertexArray(plugin.vaoTri);
			glDrawArrays(GL_TRIANGLES, 0, 3);
		} else {
			glBindVertexArray(plugin.vaoQuad);
			glDrawArrays(GL_TRIANGLE_FAN, 0, 4);
		}
	}

	private void resetHideTimer() {
		becameHiddenAt = System.currentTimeMillis();
	}

	private long getTimeSinceHiding() {
		return System.currentTimeMillis() - becameHiddenAt;
	}

	private boolean getUpdatedHiddenState() {
		boolean isHidden = isHidden();
		if (isHidden && !this.isHidden)
			resetHideTimer();
		return this.isHidden = isHidden;
	}

	@Override
	public Dimension render(Graphics2D g) {
		var bounds = getBounds();
		if (getUpdatedHiddenState()) {
			// When the overlay is hidden, keep it manageable for a minute by not returning null
			if (!isManageable() || getTimeSinceHiding() >= 60000) {
				resizeStartingBounds.setSize(0, 0);
				return null;
			}
		} else if (!borderless) {
			g.setColor(Color.BLACK);
			g.drawRect(0, 0, bounds.width, bounds.height);
		}
		return bounds.getSize();
	}

	protected void drawStringShadowed(Graphics2D g, String s, float x, float y) {
		var c = g.getColor();
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
	}

	protected void drawStringCentered(Graphics2D g, String s, float x, float y) {
		var m = g.getFontMetrics();
		drawStringShadowed(g, s, x - m.stringWidth(s) / 2.f, y + m.getHeight() / 2.f);
	}

	protected void drawStringCentered(Graphics2D g, String s) {
		var b = g.getClipBounds();
		drawStringCentered(g, s, b.width / 2.f, b.height / 2.f);
	}
}
