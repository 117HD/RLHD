package rs117.hd.utils;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.overlay.OverlayManager;
import rs117.hd.overlays.TileInfoOverlay;

import javax.inject.Inject;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

@Slf4j
public class DeveloperTools implements KeyListener
{

	// This could be part of the config if we had developer mode config sections
	private static final Keybind KEY_TOGGLE_TILE_INFO = new Keybind(KeyEvent.VK_F3, InputEvent.CTRL_DOWN_MASK);

	@Inject
	private KeyManager keyManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private TileInfoOverlay tileInfoOverlay;

	private boolean tileInfoOverlayEnabled = false;

	public void activate() {
		keyManager.registerKeyListener(this);
		if (tileInfoOverlayEnabled)
		{
			overlayManager.add(tileInfoOverlay);
		}
	}

	public void deactivate() {
		keyManager.unregisterKeyListener(this);
		overlayManager.remove(tileInfoOverlay);
	}

	@Override
	public void keyPressed(KeyEvent event)
	{
		if (KEY_TOGGLE_TILE_INFO.matches(event))
		{
			event.consume();
			tileInfoOverlayEnabled = !tileInfoOverlayEnabled;
			if (tileInfoOverlayEnabled)
			{
				overlayManager.add(tileInfoOverlay);
			}
			else
			{
				overlayManager.remove(tileInfoOverlay);
			}
		}
	}

	@Override
	public void keyReleased(KeyEvent event)
	{

	}

	@Override
	public void keyTyped(KeyEvent event)
	{

	}
}
