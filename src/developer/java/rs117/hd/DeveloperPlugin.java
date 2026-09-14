package rs117.hd;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import rs117.hd.overlays.ProfilerOverlay;
import rs117.hd.overlays.ProfilerUI;
import rs117.hd.utils.DeveloperTools;

@Slf4j
@PluginDescriptor(
	name = "117 HD Developer",
	description = "Development and profiling tools for 117 HD",
	tags = {"117", "hd", "developer", "development", "profiler"}
)
@PluginDependency(HdPlugin.class)
public class DeveloperPlugin extends Plugin implements KeyListener {
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

	@Inject
	private DeveloperConfig config;

	private Keybind configToggleFrameTimings;
	private boolean frameTimingsOverlayEnabled;

	@Provides
	DeveloperConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(DeveloperConfig.class);
	}

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

		processConfigChanges();
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
	public void onConfigChanged(ConfigChanged event) {
		if (event.getGroup().equals(DeveloperConfig.CONFIG_GROUP))
			processConfigChanges();
	}

	private void processConfigChanges() {
		setDeveloperToolsKeybind("KEY_TOGGLE_TILE_INFO", config.toggleTileInfo());
		setDeveloperToolsKeybind("KEY_RECORD_TIMINGS_SNAPSHOT", config.recordTimingsSnapshot());
		setDeveloperToolsKeybind("KEY_TOGGLE_SHADOW_MAP_OVERLAY", config.toggleShadowMapOverlay());
		setDeveloperToolsKeybind("KEY_TOGGLE_LIGHT_GIZMO_OVERLAY", config.toggleLightGizmoOverlay());
		setDeveloperToolsKeybind("KEY_TOGGLE_TILED_LIGHTING_OVERLAY", config.toggleTiledLightingOverlay());
		setDeveloperToolsKeybind("KEY_TOGGLE_FREEZE_FRAME", config.toggleFreezeFrame());
		setDeveloperToolsKeybind("KEY_TOGGLE_ORTHOGRAPHIC", config.toggleOrthographic());
		setDeveloperToolsKeybind("KEY_TOGGLE_HIDE_UI", config.toggleHideUi());
		setDeveloperToolsKeybind("KEY_RELOAD_SCENE", config.reloadScene());
		configToggleFrameTimings = config.toggleFrameTimings();
	}

	private void setDeveloperToolsKeybind(String fieldName, Keybind keybind) {
		try {
			Field field = DeveloperTools.class.getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(null, keybind);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Unable to configure DeveloperTools keybind " + fieldName, e);
		}
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
		if (configToggleFrameTimings.matches(e)) {
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
