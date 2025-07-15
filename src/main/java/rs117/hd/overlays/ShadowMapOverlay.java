package rs117.hd.overlays;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;
import rs117.hd.HdPlugin;
import rs117.hd.opengl.shader.ShaderException;

import static org.lwjgl.opengl.GL33C.*;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_BASE;
import static rs117.hd.HdPlugin.TEXTURE_UNIT_SHADOW_MAP;

@Slf4j
@Singleton
public class ShadowMapOverlay extends Overlay {
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private HdPlugin plugin;

	private boolean isActive;

	public ShadowMapOverlay() {
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPosition(OverlayPosition.TOP_LEFT);
		setResizable(true);
	}

	public void setActive(boolean activate) {
		if (activate == isActive)
			return;
		isActive = activate;

		if (activate) {
			overlayManager.add(this);
			plugin.enableShadowMapOverlay = true;
			eventBus.register(this);
		} else {
			overlayManager.remove(this);
			plugin.enableShadowMapOverlay = false;
			eventBus.unregister(this);
		}

		clientThread.invoke(() -> {
			try {
				plugin.recompilePrograms();
			} catch (ShaderException | IOException ex) {
				log.error("Error while recompiling shaders:", ex);
				plugin.stopPlugin();
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged) {
		if (gameStateChanged.getGameState() == GameState.LOGIN_SCREEN) {
			glUseProgram(plugin.glUiProgram);
			int uniBounds = glGetUniformLocation(plugin.glUiProgram, "shadowMapOverlayDimensions");
			if (uniBounds != -1)
				glUniform4i(uniBounds, 0, 0, 0, 0);
		}
	}

	@Override
	public Dimension render(Graphics2D g) {
		var bounds = getBounds();

		clientThread.invoke(() -> {
			if (plugin.glUiProgram == 0)
				return;

			glUseProgram(plugin.glUiProgram);
			int uniShadowMap = glGetUniformLocation(plugin.glUiProgram, "shadowMap");
			if (uniShadowMap != -1)
				glUniform1i(uniShadowMap, TEXTURE_UNIT_SHADOW_MAP - TEXTURE_UNIT_BASE);
			int uniBounds = glGetUniformLocation(plugin.glUiProgram, "shadowMapOverlayDimensions");
			if (uniBounds != -1) {
				if (client.getGameState().getState() < GameState.LOGGED_IN.getState()) {
					glUniform4i(uniBounds, 0, 0, 0, 0);
				} else {
					int canvasWidth = client.getCanvasWidth();
					int canvasHeight = client.getCanvasHeight();
					float scaleX = 1;
					float scaleY = 1;
					if (client.isStretchedEnabled()) {
						var stretchedDims = client.getStretchedDimensions();
						scaleX = (float) stretchedDims.width / canvasWidth;
						scaleY = (float) stretchedDims.height / canvasHeight;
					}
					glUniform4i(uniBounds,
						(int) Math.floor((bounds.x + 1) * scaleX), (int) Math.floor((canvasHeight - bounds.height - bounds.y) * scaleY),
						(int) Math.ceil((bounds.width - 1) * scaleX), (int) Math.ceil((bounds.height - 1) * scaleY)
					);
				}
			}

			plugin.checkGLErrors();
		});

		g.setColor(Color.BLACK);
		g.drawRect(0, 0, bounds.width, bounds.height);

		return getPreferredSize() == null ? new Dimension(256, 256) : getPreferredSize();
	}
}
