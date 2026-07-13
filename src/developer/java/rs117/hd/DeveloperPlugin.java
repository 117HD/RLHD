package rs117.hd;

import java.awt.event.KeyEvent;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import rs117.hd.overlays.ProfilerOverlay;
import rs117.hd.overlays.ProfilerUI;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;

@Slf4j
@PluginDescriptor(
	name = "117 HD Developer",
	description = "Development and profiling tools for 117 HD",
	tags = {"117", "hd", "developer", "development", "profiler"}
)
@PluginDependency(HdPlugin.class)
public class DeveloperPlugin extends Plugin implements KeyListener {
	private static final Keybind KEY_TOGGLE_FRAME_TIMINGS = new Keybind(KeyEvent.VK_F4, CTRL_DOWN_MASK);

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ProfilerOverlay profilerOverlay;

	@Inject
	private ProfilerUI profilerUI;

	private boolean frameTimingsOverlayEnabled;

	@Override
	protected void startUp() {
		activate();
	}

	@Override
	protected void shutDown() {
		deactivate();
	}

	private void activate() {
		eventBus.register(this);
		keyManager.registerKeyListener(this);

		profilerUI.load();
		frameTimingsOverlayEnabled = profilerUI.loadOverlayEnabled();

		clientThread.invokeLater(() -> {
			profilerOverlay.setActive(frameTimingsOverlayEnabled);
			if (frameTimingsOverlayEnabled)
				profilerUI.applyGraphOverlayState();
		});
	}

	private void deactivate() {
		eventBus.unregister(this);
		keyManager.unregisterKeyListener(this);
		profilerOverlay.setActive(false);
		profilerUI.setGraphOverlayActive(false);
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted commandExecuted) {
		if (!commandExecuted.getCommand().equalsIgnoreCase("117hd"))
			return;

		String[] args = commandExecuted.getArguments();
		if (args.length < 1)
			return;

		String action = args[0].toLowerCase();
		switch (action) {
			case "timers":
			case "timings":
				profilerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
				profilerUI.saveOverlayEnabled(frameTimingsOverlayEnabled);
				if (!frameTimingsOverlayEnabled)
					profilerUI.setGraphOverlayActive(false);
				else
					profilerUI.applyGraphOverlayState();
				break;
			case "graph":
				profilerUI.toggleGraph();
				if (profilerUI.isGraphEnabled()) {
					profilerOverlay.setActive(frameTimingsOverlayEnabled = true);
				}
				break;
		}
	}

	@Override
	public void keyPressed(KeyEvent e) {
		if (KEY_TOGGLE_FRAME_TIMINGS.matches(e)) {
			profilerOverlay.setActive(frameTimingsOverlayEnabled = !frameTimingsOverlayEnabled);
			profilerUI.saveOverlayEnabled(frameTimingsOverlayEnabled);
			if (!frameTimingsOverlayEnabled)
				profilerUI.setGraphOverlayActive(false);
			else
				profilerUI.applyGraphOverlayState();
		} else {
			return;
		}
		e.consume();
	}

	@Override
	public void keyReleased(KeyEvent e) {}

	@Override
	public void keyTyped(KeyEvent e) {}
}
