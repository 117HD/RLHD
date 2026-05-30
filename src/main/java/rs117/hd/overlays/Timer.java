package rs117.hd.overlays;

import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;

import static rs117.hd.overlays.FrameTimer.ASYNC_CPU_TIMER;
import static rs117.hd.overlays.FrameTimer.ASYNC_GPU_TIMER;
import static rs117.hd.overlays.FrameTimer.CPU_TIMER;
import static rs117.hd.overlays.FrameTimer.GPU_TIMER;

@RequiredArgsConstructor
public enum Timer {
	// CPU timers

	// Draw callbacks
	DRAW_FLUSH,
	DRAW_FRAME,
	DRAW_PRESCENE,
	DRAW_SCENE,
	DRAW_ZONE_OPAQUE,
	DRAW_ZONE_ALPHA,
	DRAW_PASS,
	DRAW_DYNAMIC,
	DRAW_TEMP,
	DRAW_POSTSCENE,
	DRAW_TILED_LIGHTING,
	DRAW_SUBMIT,

	// Miscellaneous
	SWAP_BUFFERS,
	EXECUTE_COMMAND_BUFFER,
	MAP_UI_BUFFER("Map UI Buffer"),
	COPY_UI("Copy UI"),
	MODEL_UPLOAD_COMPLETE,

	// Logic
	VISIBILITY_CHECK,
	UPDATE_SCENE,
	UPDATE_PARTICLES("Update Particles"),
	UPDATE_ENVIRONMENT,
	UPDATE_LIGHTS,
	UPDATE_AREA_HIDING,
	GARBAGE_COLLECTION,
	REPLACE_FISHING_SPOTS,
	CHARACTER_DISPLACEMENT,

	// Legacy
	GET_MODEL,
	DRAW_RENDERABLE,
	CLICKBOX_CHECK,
	MODEL_BATCHING,
	MODEL_PUSHING,
	MODEL_PUSHING_VERTEX,
	MODEL_PUSHING_NORMAL,
	MODEL_PUSHING_UV("Model pushing UV"),
	IMPOSTOR_TRACKING,

	// Async CPU timers
	COPY_UI_ASYNC(ASYNC_CPU_TIMER, "Copy UI Async"),
	DRAW_TEMP_ASYNC(ASYNC_CPU_TIMER),
	DRAW_DYNAMIC_ASYNC(ASYNC_CPU_TIMER),
	STATIC_ALPHA_SORT(ASYNC_CPU_TIMER),

	// GPU timers
	RENDER_FRAME(GPU_TIMER),
	RENDER_TILED_LIGHTING(GPU_TIMER),
	UPLOAD_GEOMETRY(GPU_TIMER),
	UPLOAD_UI(GPU_TIMER, "Upload UI"),
	COMPUTE(GPU_TIMER),
	UNMAP_ROOT_CTX(GPU_TIMER),
	CLEAR_SCENE(GPU_TIMER),
	RENDER_SHADOWS(GPU_TIMER),
	RENDER_SCENE(GPU_TIMER),
	RENDER_PARTICLES(GPU_TIMER, "Render Particles"),
	RENDER_UI(GPU_TIMER, "Render UI"),
	;

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

	public boolean isAsyncCpuTimer() {
		return type == ASYNC_CPU_TIMER;
	}

	public boolean isGpuTimer() {
		return type == GPU_TIMER || type == ASYNC_GPU_TIMER;
	}

	public boolean hasGpuDebugGroup() {
		return type == GPU_TIMER;
	}

	@Override
	public String toString() {
		return name;
	}
}
