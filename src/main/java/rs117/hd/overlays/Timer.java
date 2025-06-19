package rs117.hd.overlays;

import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public enum Timer {
	DRAW_FRAME,
	DRAW_SCENE,
	DRAW_RENDERABLE,
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
	MAP_UI_BUFFER(false, "Map UI Buffer"),
	COPY_UI(false, "Copy UI"),
	RENDER_FRAME(true, false),
	UPLOAD_GEOMETRY(true),
	UPLOAD_UI(true, "Upload UI"),
	COMPUTE(true),
	CLEAR_SCENE(true),
	RENDER_SHADOWS(true),
	RENDER_SCENE(true),
	RENDER_UI(true, "Render UI"),
	SWAP_BUFFERS,
	;

	public final boolean isGpuTimer;
	public final String name;
	public final boolean gpuDebugGroup;

	Timer() {
		isGpuTimer = false;
		gpuDebugGroup = false;
		name = enumToName(name());
	}

	Timer(boolean isGpuTimer) {
		this.isGpuTimer = isGpuTimer;
		name = enumToName(name());
		gpuDebugGroup = true;
	}

	Timer(boolean isGpuTimer, @Nonnull String name) {
		this.isGpuTimer = isGpuTimer;
		this.name = name;
		gpuDebugGroup = true;
	}

	Timer(boolean isGpuTimer, boolean gpuDebugGroup) {
		this.isGpuTimer = isGpuTimer;
		this.gpuDebugGroup = gpuDebugGroup;
		name = enumToName(name());
	}

	Timer(@Nonnull String name) {
		isGpuTimer = false;
		this.name = name;
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
