package rs117.hd.overlays;

import lombok.RequiredArgsConstructor;
import net.runelite.client.util.Text;

@RequiredArgsConstructor
public enum Timer {
	DRAW_SCENE,
	GET_MODEL,
	VISIBILITY_CHECK,
	CLICKBOX_CHECK,
	DRAW_RENDERABLE,
	MODEL_BATCHING,
	MODEL_PUSHING,
	UPDATE_ENVIRONMENT,
	UPDATE_LIGHTS,
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

	Timer() {
		isGpuTimer = false;
		name = Text.titleCase(this);
	}

	Timer(boolean isGpuTimer) {
		this.isGpuTimer = isGpuTimer;
		name = Text.titleCase(this);
	}

	Timer(String name) {
		isGpuTimer = false;
		this.name = name;
	}

	@Override
	public String toString() {
		return name == null ? name() : name;
	}
}
