package rs117.hd;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;
import rs117.hd.config.SceneShaderDebugMode;

import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;

@ConfigGroup("117hd-developer")
public interface DeveloperConfig extends Config {
	String CONFIG_GROUP = "117hd-developer";

	String KEY_SCENE_SHADER_DEBUG_MODE = "sceneShaderDebugMode";
	@ConfigItem(
		keyName = KEY_SCENE_SHADER_DEBUG_MODE,
		position = 0,
		name = "Scene Shader Debug Mode",
		description = "Toggle the tile information overlay."
	)
	default SceneShaderDebugMode sceneShaderDebugMode() { return SceneShaderDebugMode.NONE; }

	String KEY_TOGGLE_TILE_INFO = "toggleTileInfo";
	@ConfigItem(
		keyName = KEY_TOGGLE_TILE_INFO,
		position = 1,
		name = "Toggle tile info",
		description = "Toggle the tile information overlay."
	)
	default Keybind toggleTileInfo() { return new Keybind(java.awt.event.KeyEvent.VK_F3, CTRL_DOWN_MASK); }

	String KEY_TOGGLE_FRAME_TIMINGS = "toggleFrameTimings";
	@ConfigItem(
		keyName = KEY_TOGGLE_FRAME_TIMINGS,
		position = 2,
		name = "Toggle frame timings",
		description = "Toggle the frame timing overlay."
	)
	default Keybind toggleFrameTimings() { return new Keybind(java.awt.event.KeyEvent.VK_F4, CTRL_DOWN_MASK); }

	String KEY_RECORD_TIMINGS_SNAPSHOT = "recordTimingsSnapshot";
	@ConfigItem(
		keyName = KEY_RECORD_TIMINGS_SNAPSHOT,
		position = 3,
		name = "Record timings snapshot",
		description = "Capture a frame timing snapshot."
	)
	default Keybind recordTimingsSnapshot() { return new Keybind(java.awt.event.KeyEvent.VK_F4, CTRL_DOWN_MASK | SHIFT_DOWN_MASK); }

	String KEY_TOGGLE_SHADOW_MAP_OVERLAY = "toggleShadowMapOverlay";
	@ConfigItem(
		keyName = KEY_TOGGLE_SHADOW_MAP_OVERLAY,
		position = 4,
		name = "Toggle shadow map overlay",
		description = "Toggle the shadow map overlay."
	)
	default Keybind toggleShadowMapOverlay() { return new Keybind(java.awt.event.KeyEvent.VK_F5, CTRL_DOWN_MASK); }

	String KEY_TOGGLE_LIGHT_GIZMO_OVERLAY = "toggleLightGizmoOverlay";
	@ConfigItem(
		keyName = KEY_TOGGLE_LIGHT_GIZMO_OVERLAY,
		position = 5,
		name = "Toggle light gizmo overlay",
		description = "Toggle the light gizmo overlay."
	)
	default Keybind toggleLightGizmoOverlay() { return new Keybind(java.awt.event.KeyEvent.VK_F6, CTRL_DOWN_MASK); }

	String KEY_TOGGLE_TILED_LIGHTING_OVERLAY = "toggleTiledLightingOverlay";
	@ConfigItem(
		keyName = KEY_TOGGLE_TILED_LIGHTING_OVERLAY,
		position = 6,
		name = "Toggle tiled lighting overlay",
		description = "Toggle the tiled lighting overlay."
	)
	default Keybind toggleTiledLightingOverlay() { return new Keybind(java.awt.event.KeyEvent.VK_F7, CTRL_DOWN_MASK); }

	String KEY_TOGGLE_FREEZE_FRAME = "toggleFreezeFrame";
	@ConfigItem(
		keyName = KEY_TOGGLE_FREEZE_FRAME,
		position = 7,
		name = "Toggle freeze frame",
		description = "Freeze or unfreeze the current frame."
	)
	default Keybind toggleFreezeFrame() { return new Keybind(java.awt.event.KeyEvent.VK_ESCAPE, SHIFT_DOWN_MASK); }

	String KEY_TOGGLE_ORTHOGRAPHIC = "toggleOrthographic";
	@ConfigItem(
		keyName = KEY_TOGGLE_ORTHOGRAPHIC,
		position = 8,
		name = "Toggle orthographic projection",
		description = "Toggle orthographic projection."
	)
	default Keybind toggleOrthographic() { return new Keybind(java.awt.event.KeyEvent.VK_TAB, SHIFT_DOWN_MASK); }

	String KEY_TOGGLE_HIDE_UI = "toggleHideUi";
	@ConfigItem(
		keyName = KEY_TOGGLE_HIDE_UI,
		position = 9,
		name = "Toggle UI visibility",
		description = "Toggle the RuneLite UI."
	)
	default Keybind toggleHideUi() { return new Keybind(java.awt.event.KeyEvent.VK_H, CTRL_DOWN_MASK); }

	String KEY_RELOAD_SCENE = "reloadScene";
	@ConfigItem(
		keyName = KEY_RELOAD_SCENE,
		position = 10,
		name = "Reload scene",
		description = "Reload the current scene."
	)
	default Keybind reloadScene() { return new Keybind(java.awt.event.KeyEvent.VK_R, CTRL_DOWN_MASK); }
}
