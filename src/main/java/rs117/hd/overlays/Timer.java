package rs117.hd.overlays;

import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum Timer {
	DRAW_FRAME,
	DRAW_SCENE,
	DRAW_RENDERABLE,
	DRAW_TILED_LIGHTING,
	GET_MODEL,
	VISIBILITY_CHECK,
	CLICKBOX_CHECK,
	MODEL_BATCHING,
	MODEL_PUSHING,
	MODEL_PUSHING_VERTEX,
	MODEL_PUSHING_NORMAL,
	MODEL_PUSHING_UV(false, "Model pushing UV"),
	UPDATE_ENVIRONMENT,
	UPDATE_LIGHTS,
	IMPOSTOR_TRACKING,
	REPLACE_FISHING_SPOTS,
	CHARACTER_DISPLACEMENT,
	MAP_UI_BUFFER(false, "Map UI Buffer"),
	COPY_UI(false, "Copy UI"),
	RENDER_FRAME(true, false),
	RENDER_TILED_LIGHTING(true),
	UPLOAD_GEOMETRY(true),
	UPLOAD_UI(true, "Upload UI"),
	COMPUTE(true),
	CLEAR_SCENE(true),
	RENDER_SHADOWS(true),
	RENDER_SCENE(true),
	RENDER_UI(true, "Render UI"),
	SWAP_BUFFERS,
	;

	public final String name;
	public final boolean isGpuTimer;
	public final boolean gpuDebugGroup;

	Timer() {
		name = enumToName(name());
		isGpuTimer = false;
		gpuDebugGroup = false;
	}

	Timer(boolean isGpuTimer) {
		name = enumToName(name());
		this.isGpuTimer = isGpuTimer;
		gpuDebugGroup = isGpuTimer;
	}

	Timer(boolean isGpuTimer, @Nonnull String name) {
		this.name = name;
		this.isGpuTimer = isGpuTimer;
		gpuDebugGroup = isGpuTimer;
	}

	Timer(boolean isGpuTimer, boolean gpuDebugGroup) {
		name = enumToName(name());
		this.isGpuTimer = isGpuTimer;
		this.gpuDebugGroup = gpuDebugGroup;
	}

	Timer(@Nonnull String name) {
		this.name = name;
		isGpuTimer = false;
		gpuDebugGroup = false;
	}

	private static String enumToName(String name) {
		name = name.replace('_', ' ');
		return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
	}

	@Override
	public String toString() {
		return name;
	}
}
