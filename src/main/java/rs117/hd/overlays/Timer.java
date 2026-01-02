package rs117.hd.overlays;

import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;

import static rs117.hd.overlays.FrameTimer.ASYNC_TIMER;
import static rs117.hd.overlays.FrameTimer.CPU_TIMER;
import static rs117.hd.overlays.FrameTimer.GPU_GROUP_TIMER;
import static rs117.hd.overlays.FrameTimer.GPU_TIMER;

@RequiredArgsConstructor
public enum Timer {
	// CPU Timers

	// Zone Renderer Draw Callbacks
	DRAW_FLUSH,
	DRAW_FRAME,
	DRAW_PRESCENE,
	DRAW_SCENE,
	DRAW_PASS,
	DRAW_DYNAMIC,
	DRAW_TEMP,
	DRAW_POSTSCENE,
	DRAW_TILED_LIGHTING,
	DRAW_SUBMIT,
	SWAP_BUFFERS,
	EXECUTE_COMMAND_BUFFER,
	MAP_UI_BUFFER("Map UI Buffer"),
	COPY_UI("Copy UI"),
	MODEL_UPLOAD_COMPLETE,

	// Logic Timers
	VISIBILITY_CHECK,
	UPDATE_SCENE,
	UPDATE_ENVIRONMENT,
	UPDATE_LIGHTS,
	UPDATE_AREA_HIDING,
	GARBAGE_COLLECTION,
	REPLACE_FISHING_SPOTS,
	CHARACTER_DISPLACEMENT,

	// Legacy Timers
	GET_MODEL,
	DRAW_RENDERABLE,
	CLICKBOX_CHECK,
	MODEL_BATCHING,
	MODEL_PUSHING,
	MODEL_PUSHING_VERTEX,
	MODEL_PUSHING_NORMAL,
	MODEL_PUSHING_UV("Model pushing UV"),
	IMPOSTOR_TRACKING,

	// Async Timers
	COPY_UI_ASYNC(ASYNC_TIMER, "Copy UI Async"),
	DRAW_TEMP_ASYNC(ASYNC_TIMER),
	DRAW_DYNAMIC_ASYNC(ASYNC_TIMER),
	STATIC_ALPHA_SORT(ASYNC_TIMER),

	// GPU Timers
	RENDER_FRAME(GPU_TIMER),
	RENDER_TILED_LIGHTING(GPU_GROUP_TIMER),
	UPLOAD_GEOMETRY(GPU_TIMER),
	UPLOAD_UI(GPU_GROUP_TIMER, "Upload UI"),
	COMPUTE(GPU_GROUP_TIMER),
	CLEAR_SCENE(GPU_GROUP_TIMER),
	RENDER_SHADOWS(GPU_GROUP_TIMER),
	RENDER_SCENE(GPU_GROUP_TIMER),
	RENDER_UI(GPU_GROUP_TIMER, "Render UI");

	public static final Timer[] TIMERS = values();
	public final String name;
	public final int type;

	Timer() {
		name = enumToName(name());
		type = CPU_TIMER;
	}

	Timer(int type) {
		name = enumToName(name());
		this.type = type;
	}

	Timer(@Nonnull String name) {
		this.name = name;
		type = CPU_TIMER;
	}

	Timer(int type, @Nonnull String name) {
		this.name = name;
		this.type = type;
	}

	private static String enumToName(String name) {
		name = name.replace('_', ' ');
		return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
	}

	public boolean isCpuTimer() {
		return type == CPU_TIMER;
	}

	public boolean isGpuTimer() {
		return type == GPU_TIMER || type == GPU_GROUP_TIMER;
	}

	public boolean isGpuGroupTimer() {
		return type == GPU_GROUP_TIMER;
	}

	public boolean isThreadTimer() {
		return type == ASYNC_TIMER;
	}

	@Override
	public String toString() {
		return name;
	}
}
